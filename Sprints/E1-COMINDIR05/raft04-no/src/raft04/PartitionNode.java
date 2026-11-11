package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Um processo por (partição, nó). Ordem de locks: monitor do nó -> RaftState / RecordFile / log.
 * Nenhum destes auxiliares volta a adquirir o monitor do nó.
 */
public final class PartitionNode implements AutoCloseable {
    private final Object monitor = new Object();
    private final int partition;
    private final int self;
    private final int n;
    private final Partitioner partitioner;
    private final RecordFile file;
    private final MonitorLog log;
    private final IpfsPubSub pubsub;
    private final RaftState raft;
    private final RecoveryClient client;
    private final NavigableMap<Long, SensorRecord> records = new TreeMap<>();
    private final ScheduledExecutorService verification;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final List<Thread> subscriptions = new ArrayList<>();
    private volatile boolean closed;
    private volatile boolean failed;
    private RecoveryServer server;
    private ScheduledExecutorService publication;
    private ScheduledFuture<?> publicationTask;

    // Campos protegidos pelo monitor do nó, incluindo todas as mudanças de termo.
    private long lastSeq;
    private long lastTerm;
    private long seq;
    private long lamport;
    private VectorClock vector;
    private long hb;
    private long verifiedLeaderTerm = -1;
    private int verifiedLeader;
    private List<SensorRecord> pending = new ArrayList<>();

    PartitionNode(int partition, int self, int n, long started) throws IOException {
        Wire.validateHeartbeatConfig();
        partitioner = new Partitioner(n);
        if (partition < 0 || partition >= n || self < 1 || self > Wire.GRUPO)
            throw new IllegalArgumentException("PartitionNode <k> <i> [N]: k em 0..N-1 e i em 1..3");
        this.partition = partition;
        this.self = self;
        this.n = n;
        log = new MonitorLog("eventos-p" + partition + "-n" + self + ".txt");
        file = new RecordFile("no-p" + partition + "-n" + self + ".txt");
        file.ensureExists();
        for (SensorRecord record : file.range(1, Long.MAX_VALUE)) {
            validate(record);
            if (record.getSeq() != lastSeq + 1) throw new IOException("histórico local com lacunas");
            records.put(record.getSeq(), record);
            lastSeq = record.getSeq();
            lastTerm = record.getTerm();
        }
        pubsub = new IpfsPubSub(System.getProperty("raft04.api", "http://127.0.0.1:5001/api/v0"));
        raft = new RaftState(partition, self, Wire.GRUPO, Wire.ELEICAO_MIN_MS, Wire.ELEICAO_MAX_MS,
                Path.of("estado-p" + partition + "-n" + self + ".txt"), log);
        client = new RecoveryClient(partition, self, n);
        verification = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "verificacao"));
        vector = new VectorClock(n);
        log.acceptAt(started, "TERMO;p" + partition + ";arranque=true;termo=" + raft.term()
                + ";papel=SEGUIDOR;ultimoSeq=" + lastSeq + ";ultimoTermo=" + lastTerm);
    }

    public static void main(String[] args) {
        long started = System.currentTimeMillis();
        if (args.length < 2 || args.length > 3) {
            System.err.println("Uso: PartitionNode <k> <i> [N]");
            System.exit(1);
        }
        try (PartitionNode node = new PartitionNode(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                args.length == 3 ? Integer.parseInt(args[2]) : 3, started)) {
            Runtime.getRuntime().addShutdownHook(new Thread(node::close, "fecho-no"));
            node.run();
            if (node.failed) System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println(System.currentTimeMillis() + ";ERRO;arranque;" + e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name + "-p" + partition + "-n" + self);
        thread.setDaemon(true);
        return thread;
    }

    private void run() throws IOException, InterruptedException {
        server = new RecoveryServer(partition, Wire.nodePort(partition, self), file,
                () -> Wire.isolated(partition, self), log);
        daemon(server::run, "tcp").start();
        subscribe(Wire.topicIn(partition), this::receiveInput);
        subscribe(Wire.topicData(partition), this::receiveBatch);
        subscribe(Wire.topicElection(partition), this::receiveElection);
        verification.scheduleAtFixedRate(this::tick, Wire.VERIFICACAO_MS,
                Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
        stopped.await();
    }

    private void subscribe(String topic, Consumer<String> handler) {
        Thread thread = daemon(() -> {
            while (!closed) {
                try {
                    pubsub.subscribe(topic, handler);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | RuntimeException e) {
                    if (!closed) error("subscricao=" + topic, e);
                }
                if (!closed) {
                    try { Thread.sleep(250); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
        }, "sub-" + topic);
        subscriptions.add(thread);
        thread.start();
    }

    private void tick() {
        try {
            VoteRequest request;
            synchronized (monitor) {
                if (closed) return;
                long term = raft.tick();
                if (term < 0) return;
                request = new VoteRequest(term, self, lastSeq, lastTerm);
            }
            publishElection(request.encode());
        } catch (UncheckedIOException e) {
            fatal("estado persistente", e);
        } catch (IOException | RuntimeException e) {
            error("eleicao", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publishElection(String message) throws IOException, InterruptedException {
        if (Wire.isolated(partition, self)) {
            log.accept("IGNORADO;p" + partition + ";origem=PUBLICACAO_ELEICAO;isolado=true");
            return;
        }
        pubsub.publish(Wire.topicElection(partition), message);
    }

    private void receiveElection(String message) {
        if (closed || Wire.isolated(partition, self)) return;
        try {
            String type = Wire.type(message);
            if (type.equals("PEDIDO_VOTO")) {
                VoteRequest request = VoteRequest.decode(message);
                if (request.getCandidate() == self) return;
                VoteReply reply;
                synchronized (monitor) {
                    if (closed || Wire.isolated(partition, self)) return;
                    RaftState.Vote decision = raft.onVoteRequest(request.getTerm(), request.getCandidate(),
                            request.getLastSeq(), request.getLastTerm(), lastSeq, lastTerm);
                    stopPublicationIfFollower();
                    reply = new VoteReply(decision.term, self, request.getCandidate(), decision.granted);
                }
                publishElection(reply.encode());
            } else if (type.equals("VOTO")) {
                VoteReply reply = VoteReply.decode(message);
                if (reply.getVoter() == self) return;
                synchronized (monitor) {
                    if (closed || Wire.isolated(partition, self)) return;
                    // Também observa termos superiores em votos dirigidos a outros candidatos.
                    boolean elected = raft.onVote(reply.getTerm(), reply.getVoter(),
                            reply.getCandidate(), reply.isGranted());
                    stopPublicationIfFollower();
                    if (elected) becomeLeader();
                }
            } else {
                throw new IllegalArgumentException("mensagem de eleição desconhecida");
            }
        } catch (UncheckedIOException e) {
            fatal("estado persistente", e);
        } catch (IOException | RuntimeException e) {
            error("mensagem de eleicao", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Chamado com o monitor do nó, depois de RaftState confirmar a maioria. */
    private void becomeLeader() {
        SensorRecord last = records.isEmpty() ? null : records.lastEntry().getValue();
        seq = lastSeq;
        lamport = last == null ? 0 : last.getLamport();
        vector = last == null ? new VectorClock(n) : last.getVector();
        hb = 0;
        pending = new ArrayList<>();
        verifiedLeaderTerm = raft.term();
        verifiedLeader = self;
        if (publicationTask != null) publicationTask.cancel(false);
        if (publication != null) publication.shutdown();
        publication = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "lote"));
        long leadershipTerm = raft.term();
        publicationTask = publication.scheduleAtFixedRate(() -> sendBatch(leadershipTerm),
                0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPublicationIfFollower() {
        if (raft.termIfLeader() >= 0) return;
        if (publicationTask != null) publicationTask.cancel(false);
        if (publication != null) publication.shutdown();
        publicationTask = null;
        publication = null;
        pending = new ArrayList<>();
    }

    private void receiveInput(String message) {
        try {
            String[] fields = message.split(";", -1);
            if (fields.length != 5 || !fields[0].equals("IN")) throw new IllegalArgumentException("IN mal formado");
            String sensor = fields[1];
            double temperature = Double.parseDouble(fields[2]);
            long receivedLamport = Long.parseLong(fields[3]);
            String[] components = fields[4].split(",", -1);
            if (components.length != n || receivedLamport < 0 || !Double.isFinite(temperature)
                    || partitioner.of(sensor) != partition) throw new IllegalArgumentException("IN incompatível com a partição");
            long[] values = new long[n];
            for (int k = 0; k < n; k++) {
                values[k] = Long.parseLong(components[k]);
                if (values[k] < 0) throw new IllegalArgumentException("vetor negativo");
            }
            VectorClock receivedVector = new VectorClock(values);
            synchronized (monitor) {
                long term = raft.termIfLeader();
                if (closed || term < 0) {
                    log.accept("IGNORADO;p" + partition + ";origem=IN;sensor=" + sensor + ";motivo=nao_lider");
                    return;
                }
                long next = Math.addExact(seq, 1);
                long nextLamport = Math.addExact(Math.max(lamport, receivedLamport), 1);
                VectorClock nextVector = vector.copy();
                nextVector.merge(receivedVector);
                nextVector.increment(partition);
                if (nextVector.get(partition) != next)
                    throw new IllegalArgumentException("V[" + partition + "] difere de seq; relogios inconsistentes");
                SensorRecord record = SensorRecord.now(next, term, sensor, temperature, nextLamport, nextVector);
                append(record);
                seq = next;
                lamport = nextLamport;
                vector = nextVector;
                pending.add(record);
                log.accept("RECEBIDO;p" + partition + ";origem=IN;termo=" + term + ";seq=" + seq
                        + ";registo64=" + encodeRecords(List.of(record)));
            }
        } catch (IOException | RuntimeException e) {
            error("IN", e);
        }
    }

    private void sendBatch(long leadershipTerm) {
        Batch batch;
        synchronized (monitor) {
            long term = raft.termIfLeader();
            if (closed || term < 0 || term != leadershipTerm) return;
            List<SensorRecord> snapshot = pending;
            pending = new ArrayList<>();
            batch = new Batch(term, self, "127.0.0.1", Wire.nodePort(partition, self),
                    ++hb, lastSeq, lastTerm, snapshot);
        }
        try {
            if (Wire.isolated(partition, self)) {
                log.accept("IGNORADO;p" + partition + ";origem=PUBLICACAO_LOTE;isolado=true");
                return;
            }
            String message = batch.encode();
            long instant = System.currentTimeMillis();
            pubsub.publish(Wire.topicData(partition), message);
            String line = batch.getTerm() + ";" + batch.getHb() + ";" + instant + ";"
                    + batch.getLastSeq() + ";" + batch.getLastTerm() + ";"
                    + batch.getRecords().size() + ";" + message.getBytes(StandardCharsets.UTF_8).length + "\n";
            Files.writeString(Path.of("hb-p" + partition + "-n" + self + ".txt"), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            error("publicacao LOTE", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void receiveBatch(String message) {
        if (closed || Wire.isolated(partition, self)) return;
        try {
            Batch batch = Batch.decode(message, n);
            if (batch.getLeader() == self) return;
            for (SensorRecord record : batch.getRecords()) validate(record);
            synchronized (monitor) {
                if (closed || Wire.isolated(partition, self)) return;
                boolean accepted = raft.onBatch(batch.getTerm(), batch.getLeader());
                stopPublicationIfFollower();
                if (!accepted) {
                    log.accept("IGNORADO;p" + partition + ";origem=LOTE;termoMsg="
                            + batch.getTerm() + ";atual=" + raft.term());
                    return;
                }
                log.accept(batch.receivedEvent(partition) + ";origem=LOTE;atual=" + raft.term());
                if (lastSeq > batch.getLastSeq()
                        || (lastSeq > 0 && lastSeq == batch.getLastSeq() && lastTerm != batch.getLastTerm())) {
                    reconcile(batch);
                } else if (lastSeq > 0 && (verifiedLeaderTerm != batch.getTerm()
                        || verifiedLeader != batch.getLeader())) {
                    // Uma comparação sobreposta evita esconder um prefixo divergente ao acrescentar uma cauda.
                    List<SensorRecord> overlap = client.fetch(batch.getAddress(), batch.getPort(),
                            lastSeq, lastSeq, batch.getTerm());
                    complete(overlap, lastSeq, lastSeq);
                    if (!same(records.get(lastSeq), overlap.get(0))) reconcile(batch);
                }
                for (SensorRecord record : batch.getRecords()) {
                    SensorRecord existing = records.get(record.getSeq());
                    if (existing != null) {
                        if (!same(existing, record)) { reconcile(batch); break; }
                        continue;
                    }
                    if (record.getSeq() > lastSeq + 1) recoverMissing(record.getSeq() - 1, batch);
                    existing = records.get(record.getSeq());
                    if (existing == null) append(record);
                    else if (!same(existing, record)) { reconcile(batch); break; }
                }
                if (lastSeq < batch.getLastSeq()) recoverMissing(batch.getLastSeq(), batch);
                if (lastSeq > batch.getLastSeq()
                        || (lastSeq > 0 && lastSeq == batch.getLastSeq() && lastTerm != batch.getLastTerm()))
                    reconcile(batch);
                verifiedLeaderTerm = batch.getTerm();
                verifiedLeader = batch.getLeader();
            }
        } catch (UncheckedIOException e) {
            fatal("estado persistente", e);
        } catch (IOException | RuntimeException e) {
            error("LOTE/recuperacao", e);
        }
    }

    private void validate(SensorRecord record) {
        if (record.getVector().size() != n || record.getVector().get(partition) != record.getSeq()
                || partitioner.of(record.getSensorId()) != partition)
            throw new IllegalArgumentException("registo incompatível com p" + partition);
    }

    private void append(SensorRecord record) throws IOException {
        validate(record);
        if (record.getSeq() != lastSeq + 1) throw new IOException("append fora da sequência");
        file.append(record);
        records.put(record.getSeq(), record);
        lastSeq = record.getSeq();
        lastTerm = record.getTerm();
    }

    private static boolean same(SensorRecord a, SensorRecord b) {
        return a != null && b != null && a.getSeq() == b.getSeq() && a.getTerm() == b.getTerm();
    }

    private static void complete(List<SensorRecord> fetched, long from, long to) throws IOException {
        if (fetched.size() != to - from + 1) throw new IOException("resposta TCP incompleta: " + from + ".." + to);
    }

    private void recoverMissing(long to, Batch batch) throws IOException {
        if (lastSeq >= to) return;
        long previous = lastSeq;
        long from = lastSeq == 0 ? 1 : lastSeq;
        List<SensorRecord> fetched = client.fetch(batch.getAddress(), batch.getPort(), from, to, batch.getTerm());
        complete(fetched, from, to);
        if (lastSeq > 0 && !same(records.get(lastSeq), fetched.get(0))) {
            reconcile(batch);
            return;
        }
        for (SensorRecord record : fetched) if (record.getSeq() > lastSeq) append(record);
        log.accept("RECUPERACAO;p" + partition + ";de=" + (previous + 1) + ";ate=" + to + ";n=" + (lastSeq - previous));
    }

    /** Recua um bloco de termo de cada vez até encontrar uma fronteira comum. */
    private void reconcile(Batch batch) throws IOException {
        long target = batch.getLastSeq();
        if (target == 0) {
            replaceHistory(List.of(), 1);
            return;
        }
        long start = records.isEmpty() ? 1 : blockStart(records.lastKey());
        List<SensorRecord> fetched;
        while (true) {
            if (start > target) {
                start = start == 1 ? 1 : blockStart(start - 1);
                continue;
            }
            fetched = client.fetch(batch.getAddress(), batch.getPort(), start, target, batch.getTerm());
            complete(fetched, start, target);
            if (start == 1 || same(records.get(start), fetched.get(0))) break;
            start = blockStart(start - 1);
        }
        if (fetched.get(fetched.size() - 1).getTerm() != batch.getLastTerm())
            throw new IOException("snapshot TCP já não corresponde ao cabeçalho do líder");
        List<SensorRecord> replacement = new ArrayList<>(records.headMap(start, false).values());
        replacement.addAll(fetched);
        long difference = Long.MAX_VALUE;
        for (SensorRecord old : records.tailMap(start, true).values()) {
            int index = (int) (old.getSeq() - start);
            if (index >= fetched.size() || !same(old, fetched.get(index))) {
                difference = old.getSeq();
                break;
            }
        }
        if (difference == Long.MAX_VALUE) {
            long previous = lastSeq;
            for (SensorRecord record : fetched) if (record.getSeq() > lastSeq) append(record);
            if (lastSeq > previous) log.accept("RECUPERACAO;p" + partition + ";de=" + (previous + 1)
                    + ";ate=" + lastSeq + ";n=" + (lastSeq - previous));
        } else {
            replaceHistory(replacement, difference);
        }
    }

    private long blockStart(long seq) {
        Map.Entry<Long, SensorRecord> entry = records.floorEntry(seq);
        if (entry == null) return 1;
        long term = entry.getValue().getTerm();
        long start = entry.getKey();
        while ((entry = records.lowerEntry(start)) != null && entry.getValue().getTerm() == term)
            start = entry.getKey();
        return start;
    }

    private void replaceHistory(List<SensorRecord> replacement, long difference) throws IOException {
        List<SensorRecord> removed = new ArrayList<>(records.tailMap(difference, true).values());
        if (removed.isEmpty() && replacement.isEmpty()) return;
        file.rewrite(replacement);
        records.clear();
        for (SensorRecord record : replacement) records.put(record.getSeq(), record);
        lastSeq = records.isEmpty() ? 0 : records.lastKey();
        lastTerm = records.isEmpty() ? 0 : records.lastEntry().getValue().getTerm();
        if (!removed.isEmpty()) {
            Set<Long> terms = removed.stream().map(SensorRecord::getTerm).collect(Collectors.toCollection(TreeSet::new));
            log.accept("TRUNCADO;p" + partition + ";de=" + difference + ";descartados=" + removed.size()
                    + ";termos=" + terms + ";registos64=" + encodeRecords(removed));
        }
    }

    private static String encodeRecords(List<SensorRecord> records) {
        String text = records.stream().map(SensorRecord::toLine).collect(Collectors.joining("\n"));
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private void error(String origin, Exception e) {
        log.accept("ERRO;p" + partition + ";origem=" + origin + ";detalhe=" + e.getClass().getSimpleName() + ":" + e.getMessage());
    }

    private void fatal(String origin, Exception e) {
        failed = true;
        error(origin, e);
        close();
    }

    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            if (publicationTask != null) publicationTask.cancel(false);
            if (publication != null) publication.shutdownNow();
        }
        verification.shutdownNow();
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        pubsub.close();
        for (Thread thread : subscriptions) thread.interrupt();
        stopped.countDown();
    }
}
