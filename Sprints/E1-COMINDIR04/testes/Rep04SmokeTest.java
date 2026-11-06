package rep04;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Testes locais com processos Java reais e uma API HTTP simulada; não mede o IPFS real. */
public final class Rep04SmokeTest {
    private static Path project;
    private static Path work;
    private static final List<Process> children = new ArrayList<>();
    private static int assertions;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static void rejected(Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException e) { rejected = true; }
        require(rejected, message);
    }

    private static String text(Path path) {
        try { return Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { return ""; }
    }

    private static long lines(Path path) {
        return text(path).lines().filter(line -> !line.isBlank()).count();
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timeout: " + message);
            Thread.sleep(25);
        }
        assertions++;
    }

    private static boolean eventAfter(Path log, String event, long since) {
        return text(log).lines().anyMatch(line -> {
            int separator = line.indexOf(';');
            if (separator < 0 || !line.contains(";" + event)) return false;
            try { return Long.parseLong(line.substring(0, separator)) >= since; }
            catch (NumberFormatException e) { return false; }
        });
    }

    private static String java() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        return bin.resolve(Files.exists(bin.resolve("java.exe")) ? "java.exe" : "java").toString();
    }

    private static String classpath(String role) {
        return project.resolve("rep04-" + role).resolve("out").toString();
    }

    private static Process start(String role, String main, String directory, String... args) throws IOException {
        Path folder = work.resolve(directory);
        Files.createDirectories(folder);
        List<String> command = new ArrayList<>(List.of(java(), "-Xmx64m", "-Drep04.periodo=1000",
                "-Drep04.fator=2", "-cp", classpath(role), "rep04." + main));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("console.log").toFile())).start();
        children.add(process);
        return process;
    }

    private static void stop(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            require(process.waitFor(5, TimeUnit.SECONDS), "O processo de teste terminou");
        }
    }

    private static void command(Process producer, String command) throws IOException {
        producer.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
        producer.getOutputStream().flush();
    }

    private static void batchAndMonitor() throws Exception {
        SensorRecord first = new SensorRecord(1, "S01", 21.0, "2026-11-06T12:00:00", 1,
                new VectorClock(new long[] {1, 0, 0}));
        SensorRecord second = new SensorRecord(2, "S04", 22.5, "2026-11-06T12:00:01", 2,
                new VectorClock(new long[] {2, 0, 0}));
        List<SensorRecord> original = new ArrayList<>(List.of(first, second));
        Batch batch = new Batch("127.0.0.1", 7000, 1000, 2, 2, original);
        original.clear();
        require(batch.getRecords().size() == 2, "O lote conserva o snapshot da lista");
        Batch decoded = Batch.decode(batch.encode().replace("\n", "\r\n") + "\r\n", 3);
        require(decoded.encode().equals(batch.encode()), "LOTE multiline/CRLF preserva os registos e cabeçalho");
        require(Batch.decode(new Batch("127.0.0.1", 7000, 1000, 3, 2, List.of()).encode(), 3)
                .getLastSeq() == 2, "Um lote vazio conserva ultimoSeq");
        rejected(() -> Batch.decode(batch.encode().replace(";2\n1;", ";3\n1;"), 3), "Rejeita contagem errada");
        rejected(() -> Batch.decode(batch.encode(), 2), "Rejeita dimensão vetorial errada");
        rejected(() -> Batch.decode("LOTE;127.0.0.1;7000;1000;1;0;1", 3), "Rejeita corpo ausente");
        rejected(() -> Batch.decode("REG;127.0.0.1;7000;1", 3), "Rejeita o protocolo antigo");
        List<String> producerEvents = new ArrayList<>();
        try (Producer producer = new Producer(3, "http://127.0.0.1:1/api/v0", producerEvents::add)) {
            producer.receiveHeartbeat(0, "LOTE;127.0.0.1;7000;1000;1;1;1\ncorpo nao interpretado");
            require(producerEvents.stream().anyMatch(e -> e.startsWith("RECEBIDO;p0;")),
                    "O produtor interpreta apenas o cabecalho, sem descodificar o corpo");
        }

        List<String> events = new ArrayList<>();
        HeartbeatMonitor monitor = new HeartbeatMonitor(0, 5, 1, events::add);
        monitor.onHeartbeat(1000, 7);
        Thread.sleep(20);
        monitor.check();
        require(monitor.state() == HeartbeatMonitor.State.SUSPEITO, "O silêncio gera suspeita");
        monitor.onHeartbeat(1000, 8);
        require(monitor.state() == HeartbeatMonitor.State.ATIVO
                && events.stream().anyMatch(e -> e.startsWith("RETOMA;")), "Retoma sem reinício");
        monitor.onHeartbeat(1000, 10);
        require(events.stream().anyMatch(e -> e.startsWith("HB_PERDIDO;")), "Salto de hb é observado");
        int previousEvents = events.size();
        monitor.onHeartbeat(999, 500);
        require(events.size() == previousEvents, "Incarnação antiga não altera o detetor");
        Thread.sleep(20);
        monitor.check();
        monitor.onHeartbeat(1000, 10);
        require(monitor.state() == HeartbeatMonitor.State.SUSPEITO, "Duplicados não renovam o timeout");
        monitor.onHeartbeat(2000, 1);
        require(events.stream().anyMatch(e -> e.startsWith("REINICIO;")), "Nova incarnação gera REINICIO");

        Path oldData = work.resolve("registos-atrasados.txt");
        PartitionState state = new PartitionState(9, 0, 3, new RecordFile(oldData.toString()), 0, -1, e -> {});
        state.receive(new Batch("127.0.0.1", 7000, 2000, 1, 0, List.of()).encode());
        state.receive(new Batch("127.0.0.1", 7000, 1000, 9, 1, List.of(first)).encode());
        require(state.lastSeq() == 1, "Registos de uma incarnação antiga continuam a ser aplicados");
        System.out.println("OK: formato LOTE, validações, monitores e registos atrasados");
    }

    private static Path leader(int k) { return work.resolve("lider" + k).resolve("lider-p" + k + ".txt"); }
    private static Path replica(int r, int k) {
        return work.resolve("replica" + r).resolve("replica-" + r + "-p" + k + ".txt");
    }
    private static Path monitor(int r) { return work.resolve("replica" + r).resolve("monitor-replica" + r + ".txt"); }

    private static void converge(int k, int a, int b, long count) throws Exception {
        await(() -> lines(leader(k)) == count && text(leader(k)).equals(text(replica(a, k)))
                && text(leader(k)).equals(text(replica(b, k))), "convergência das réplicas de p" + k);
    }

    private static void integration() throws Exception {
        for (int port = 7000; port <= 7002; port++) {
            try (ServerSocket available = new ServerSocket(port)) { /* não interrompe processos existentes */ }
        }
        try (MockApi api = new MockApi()) {
            String endpoint = api.endpoint();
            String port = Integer.toString(api.server.getAddress().getPort());
            start("replica", "PartitionReplica", "replica1", "1", "0,1", port);
            start("replica", "PartitionReplica", "replica2", "2", "1,2", port);
            start("replica", "PartitionReplica", "replica3", "3", "2,0", port);
            for (int k = 0; k < 3; k++) {
                final int partition = k;
                await(() -> api.subscribers(Wire.topicData(partition)) >= 2, "subscrições das réplicas");
            }
            Process[] leaders = new Process[3];
            for (int k = 0; k < 3; k++) {
                leaders[k] = start("lider", "PartitionLeader", "lider" + k,
                        Integer.toString(k), "3", "127.0.0.1", endpoint);
            }
            for (int k = 0; k < 3; k++) {
                final int partition = k;
                await(() -> api.subscribers(Wire.topicIn(partition)) >= 1, "subscrição do líder");
                await(() -> api.count(Wire.topicData(partition)) >= 1, "heartbeat inicial vazio");
            }
            start("processador", "AlarmProcessor", "processador", "3", endpoint);
            for (int k = 0; k < 3; k++) {
                final int partition = k;
                await(() -> api.subscribers(Wire.topicData(partition)) >= 3, "subscrição do processador");
            }
            Process producer = start("produtor", "Producer", "produtor", "3", endpoint);
            for (int k = 0; k < 3; k++) {
                final int partition = k;
                await(() -> api.subscribers(Wire.topicData(partition)) >= 4, "subscricao do produtor");
            }
            command(producer, "S01 21.0");
            command(producer, "S04 22.5");
            converge(0, 1, 3, 2);
            api.failData.set(true);
            command(producer, "S01 21.4");
            await(() -> api.failed.get() == 1, "falha simulada da publicação de um lote com registos");
            converge(0, 1, 3, 3);
            require(text(monitor(1)).contains("RECUPERACAO;p0;de=3;ate=3"), "Recuperação após publicação falhada");
            require(text(monitor(3)).contains("HB_PERDIDO;p0;"), "Heartbeat falhado deixa salto na numeração");
            System.out.println("OK: falha de publicação e recuperação do último registo via lote vazio/TCP");

            long before = api.count(Wire.topicData(0));
            await(() -> api.count(Wire.topicData(0)) > before, "início de um novo intervalo de lote");
            command(producer, "S01 23.0");
            await(() -> lines(leader(0)) == 4, "registo pendente persistido");
            long failedAt = System.currentTimeMillis();
            stop(leaders[0]);
            Files.writeString(work.resolve("falhas.txt"), "p0;" + failedAt + "\n", StandardCharsets.UTF_8);
            require(api.sent.stream().noneMatch(s -> !s.failed && s.topic.equals(Wire.topicData(0))
                    && s.message.contains("\n4;")), "O quarto registo não foi publicado antes da falha");
            await(() -> eventAfter(monitor(1), "SUSPEITA;p0;", failedAt), "suspeita na réplica 1");
            await(() -> eventAfter(monitor(3), "SUSPEITA;p0;", failedAt), "suspeita na réplica 3");
            await(() -> eventAfter(work.resolve("produtor/monitor-produtor.txt"), "SUSPEITA;p0;", failedAt),
                    "suspeita no produtor");
            long sentWhileDown = api.count(Wire.topicIn(0));
            command(producer, "S01 19.5");
            await(() -> api.count(Wire.topicIn(0)) > sentWhileDown, "envio mantido pelo produtor apesar da suspeita");
            await(() -> text(work.resolve("produtor/console.log")).contains("AVISO:"), "aviso no produtor");
            int subscriptions = api.subscriptionsEver(Wire.topicIn(0));
            leaders[0] = start("lider", "PartitionLeader", "lider0", "0", "3", "127.0.0.1", endpoint);
            await(() -> api.subscriptionsEver(Wire.topicIn(0)) > subscriptions, "reinício do líder");
            converge(0, 1, 3, 4);
            require(eventAfter(monitor(1), "REINICIO;p0;", failedAt), "REINICIO observado");
            require(eventAfter(monitor(1), "RETOMA;p0;", failedAt), "RETOMA observada");
            require(text(monitor(1)).contains("RECUPERACAO;p0;de=4;ate=4"), "Recupera o pendente após reinício");
            System.out.println("OK: suspeita, reinício e recuperação de um registo nunca publicado");

            command(producer, "S02 29.3");
            await(() -> text(leader(2)).contains(";ALARM-S02;29.3;"), "alarme causal enviado a p2");
            converge(1, 1, 2, 1);
            converge(2, 2, 3, 1);

            long downAt = System.currentTimeMillis();
            stop(leaders[2]);
            Files.writeString(work.resolve("falhas.txt"), "p2;" + downAt + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            Path processorLog = work.resolve("processador/monitor-processador.txt");
            await(() -> eventAfter(processorLog, "SUSPEITA;p2;", downAt), "suspeita do destino do alarme");
            long alarmCount = api.count(Wire.topicIn(2));
            command(producer, "S02 30.0");
            await(() -> api.count(Wire.topicIn(2)) > alarmCount, "publicação mantém-se apesar da suspeita");
            await(() -> text(work.resolve("processador/console.log")).contains("AVISO:"), "aviso de alarme em risco");
            converge(1, 1, 2, 2);
            producer.getOutputStream().close();
            require(producer.waitFor(5, TimeUnit.SECONDS), "O produtor termina ao receber EOF");
            require(producer.exitValue() == 0, "O produtor encerra sem erro");
            System.out.println("OK: alarmes causais e aviso sem impedir a publicação para líder suspeito");
        } finally {
            for (Process process : children) stop(process);
        }

        Path collected = work.resolve("reunidos");
        Files.createDirectories(collected);
        for (String directory : List.of("lider0", "lider1", "lider2", "replica1", "replica2", "replica3", "processador", "produtor")) {
            try (Stream<Path> files = Files.list(work.resolve(directory))) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".txt")).collect(Collectors.toList())) {
                    Files.copy(file, collected.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.copy(work.resolve("falhas.txt"), collected.resolve("falhas.txt"));
        for (String checker : List.of("PartitionCheck", "CausalCheck", "HeartbeatCheck")) {
            Path report = work.resolve(checker + ".log");
            List<String> checkCommand = new ArrayList<>(List.of(java(), "-cp", classpath("replica"), "rep04." + checker,
                    "3", collected.toString()));
            if (checker.equals("HeartbeatCheck")) checkCommand.add(collected.resolve("falhas.txt").toString());
            Process check = new ProcessBuilder(checkCommand).redirectErrorStream(true).redirectOutput(report.toFile()).start();
            if (!check.waitFor(15, TimeUnit.SECONDS)) {
                check.destroyForcibly();
                throw new AssertionError(checker + " excedeu o prazo");
            }
            require(check.exitValue() == 0, checker + ": " + text(report));
            if (checker.equals("HeartbeatCheck")) {
                require(text(report).contains("participante=produtor"), "Relatorio inclui o produtor");
                require(text(report).contains("classe=FALHA_REAL"), "Relatorio classifica as falhas observadas");
            }
            System.out.println("OK: " + checker);
        }
    }

    public static void main(String[] args) throws Exception {
        project = Path.of(args[0]).toAbsolutePath();
        work = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(work);
        try {
            batchAndMonitor();
            integration();
            System.out.println("PASS: " + assertions + " verificações. Artefactos: " + work);
        } finally {
            for (Process process : children) stop(process);
        }
    }

    private static final class Sent {
        final String topic;
        final String message;
        final boolean failed;
        Sent(String topic, String message, boolean failed) {
            this.topic = topic; this.message = message; this.failed = failed;
        }
    }

    private static final class MockApi implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final Map<String, CopyOnWriteArrayList<BlockingQueue<String>>> queues = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> ever = new ConcurrentHashMap<>();
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final AtomicBoolean failData = new AtomicBoolean();
        final AtomicInteger failed = new AtomicInteger();

        MockApi() throws IOException {
            server.createContext("/api/v0/pubsub/", this::handle);
            server.setExecutor(executor);
            server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v0"; }
        int subscribers(String topic) { return queues.getOrDefault(topic, new CopyOnWriteArrayList<>()).size(); }
        int subscriptionsEver(String topic) { return ever.getOrDefault(topic, new AtomicInteger()).get(); }
        long count(String topic) { return sent.stream().filter(s -> s.topic.equals(topic)).count(); }

        private void handle(HttpExchange exchange) throws IOException {
            String encoded = URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring(4), StandardCharsets.UTF_8);
            String topic = IpfsPubSub.fromMultibase(encoded);
            if (exchange.getRequestURI().getPath().endsWith("/sub")) {
                BlockingQueue<String> queue = new LinkedBlockingQueue<>();
                queues.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(queue);
                ever.computeIfAbsent(topic, ignored -> new AtomicInteger()).incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    while (!Thread.currentThread().isInterrupted()) {
                        String message = queue.poll(1, TimeUnit.SECONDS);
                        if (message == null) continue;
                        String json = "{\"data\":\"u" + Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(message.getBytes(StandardCharsets.UTF_8)) + "\"}\n";
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException disconnected) {
                    // O processo cliente foi terminado deliberadamente pelo teste.
                } finally {
                    queues.get(topic).remove(queue);
                    exchange.close();
                }
            } else {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String message = body.substring(body.indexOf("\r\n\r\n") + 4, body.lastIndexOf("\r\n--"));
                boolean fail = topic.equals(Wire.topicData(0)) && message.startsWith("LOTE;")
                        && !message.split("\n", 2)[0].endsWith(";0") && failData.compareAndSet(true, false);
                sent.add(new Sent(topic, message, fail));
                if (fail) failed.incrementAndGet();
                else for (BlockingQueue<String> queue : queues.getOrDefault(topic, new CopyOnWriteArrayList<>())) queue.offer(message);
                byte[] response = (fail ? "falha simulada" : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(fail ? 500 : 200, response.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(response); }
                exchange.close();
            }
        }
        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
