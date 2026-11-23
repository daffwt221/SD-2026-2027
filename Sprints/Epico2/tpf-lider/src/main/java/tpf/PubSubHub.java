package tpf;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Hub WebSocket de líder. Fase 1: autenticação, pub/sub, HB e inventário. */
public final class PubSubHub extends WebSocketServer {
    private static final SecureRandom RNG = new SecureRandom();
    private static final long TOKEN_TTL_MS = 10 * 60 * 1000L;
    private final Path registryFile;
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Registered> registered = new HashMap<>();
    private final Map<String, Long> tokens = new HashMap<>();
    private final Map<String, ReplicaStatus> live = new ConcurrentHashMap<>();

    /** O estado do heartbeat é transitório; nunca constitui prova de posse. */
    public record ReplicaStatus(String id, long quota, long used, long lastHeartbeat) {}
    private final BiConsumer<String, String> leaderReceiver;

    private static final class Registered {
        final String publicKey; final long quota;
        Registered(String publicKey, long quota) { this.publicKey = publicKey; this.quota = quota; }
    }
    private static final class Session {
        String id, publicKey, token, challenge;
        boolean authenticated;
        final Set<String> subs = new HashSet<>();
    }

    public PubSubHub(int port, Path registryFile, BiConsumer<String, String> leaderReceiver) throws IOException {
        super(new InetSocketAddress(port));
        this.registryFile = registryFile.toAbsolutePath();
        this.leaderReceiver = leaderReceiver;
        loadRegistry();
        setReuseAddr(true);
    }

    private void loadRegistry() throws IOException {
        if (!Files.exists(registryFile)) return;
        for (String line : Files.readAllLines(registryFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] c = line.split(";", -1);
            if (c.length != 3) throw new IOException("Linha inválida em replicas.txt");
            registered.put(c[0], new Registered(c[1], Long.parseLong(c[2])));
        }
    }

    /** Token de uso único válido por 10 minutos; não persistido. */
    public synchronized String gerarToken() {
        byte[] raw = new byte[32]; RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new Session());
        System.out.println("Ligação recebida: " + conn.getRemoteSocketAddress());
    }
    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = sessions.remove(conn);
        if (s != null && s.authenticated) {
            live.remove(s.id);
            System.out.println("Desligada réplica " + s.id);
        }
    }
    @Override public void onError(WebSocket conn, Exception ex) {
        System.err.println("Erro WebSocket: " + ex.getMessage());
    }
    @Override public void onStart() {
        System.out.println("PubSubHub ativo em ws://localhost:" + getPort());
        setConnectionLostTimeout(30);
    }

    @Override public synchronized void onMessage(WebSocket conn, String message) {
        Session s = sessions.get(conn);
        if (s == null) return;
        try {
            if (message.length() > 128 * 1024) throw new IllegalArgumentException("Mensagem demasiado grande");
            if (s.id == null) { acceptHello(conn, s, message); return; }
            if (!s.authenticated) { acceptProof(conn, s, message); return; }
            acceptAuthenticated(conn, s, message);
        } catch (Exception e) {
            System.err.println("ERRO de protocolo: " + e.getMessage());
            conn.close(1008, "Protocolo inválido");
        }
    }

    private void acceptHello(WebSocket conn, Session s, String message) throws Exception {
        String[] c = message.split(";", -1);
        if ((c.length != 4 && c.length != 5) || !"OLA".equals(c[0]) || !c[1].matches("[A-Za-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("Primeira mensagem tem de ser OLA");
        if (!"1".equals(c[3])) throw new IllegalArgumentException("Versão inválida");
        byte[] pub = Base64.getDecoder().decode(c[2]);
        KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pub));
        Registered old = registered.get(c[1]);
        if (old != null && !old.publicKey.equals(c[2])) throw new SecurityException("Chave pública não corresponde à registada");
        if (old == null) {
            if (c.length != 5 || !tokens.containsKey(c[4]) || tokens.get(c[4]) < System.currentTimeMillis())
                throw new SecurityException("Token ausente, inválido ou expirado");
            s.token = c[4];
        }
        s.id = c[1]; s.publicKey = c[2];
        byte[] challenge = new byte[32]; RNG.nextBytes(challenge);
        s.challenge = Base64.getEncoder().encodeToString(challenge);
        conn.send("DESAFIO;" + s.challenge);
    }

    private void acceptProof(WebSocket conn, Session s, String message) throws Exception {
        String[] c = message.split(";", -1);
        if (c.length != 3 || !"PROVA".equals(c[0]) || !s.id.equals(c[1])) throw new SecurityException("PROVA inválida");
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(s.publicKey)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(Base64.getDecoder().decode(s.challenge));
        if (!verifier.verify(Base64.getDecoder().decode(c[2]))) throw new SecurityException("Assinatura inválida");
        if (!registered.containsKey(s.id)) {
            if (s.token == null || tokens.remove(s.token) == null) throw new SecurityException("Token já usado");
            // quota inicial atualizada pelo HB; no registo é 0 até ao primeiro HB
            Path parent = registryFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(registryFile, s.id + ";" + s.publicKey + ";0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            registered.put(s.id, new Registered(s.publicKey, 0));
            System.out.println("Réplica registada: " + s.id);
        }
        s.authenticated = true; s.challenge = null;
        conn.send("AUTENTICADO;" + s.id);
        System.out.println("Autenticada réplica: " + s.id);
    }

    private void acceptAuthenticated(WebSocket conn, Session s, String msg) {
        if (msg.startsWith("SUB;")) {
            String topic = msg.substring(4);
            if (!topic.equals("tpf-r-" + s.id) && !topic.equals("tpf-ctl"))
                throw new SecurityException("Subscrição não autorizada: " + topic);
            s.subs.add(topic);
            conn.send("SUBOK;" + topic);
            return;
        }
        if (msg.startsWith("PUB;")) {
            String[] c = msg.split(";", 3);
            if (c.length != 3 || !"tpf-lider".equals(c[1])) throw new SecurityException("Publicação não autorizada");
            String data = c[2];
            if (data.startsWith("HB;") || data.startsWith("INVENTARIO;")) {
                String[] fields = data.split(";", -1);
                if (fields.length < 2 || !s.id.equals(fields[1])) throw new SecurityException("ID falso na mensagem");
                if (data.startsWith("HB;")) {
                    if (fields.length != 7) throw new IllegalArgumentException("HB inválido");
                    long quota = Long.parseLong(fields[3]);
                    long used = Long.parseLong(fields[4]);
                    if (quota < 0 || used < 0 || used > quota) throw new IllegalArgumentException("Quota/uso inválidos");
                    live.put(s.id, new ReplicaStatus(s.id, quota, used, System.currentTimeMillis()));
                }
            }
            leaderReceiver.accept(s.id, data);
            return;
        }
        throw new IllegalArgumentException("Mensagem desconhecida");
    }

    /** Só são elegíveis réplicas subscritas com HB recente (máx. 6 segundos). */
    public List<ReplicaStatus> eligibleReplicas() {
        long now = System.currentTimeMillis();
        return live.values().stream()
            .filter(r -> now - r.lastHeartbeat() <= 6000L && isSubscribed(r.id()))
            .toList();
    }

    public boolean isSubscribed(String id) {
        return sessions.values().stream().anyMatch(s -> s.authenticated && id.equals(s.id)
            && s.subs.contains("tpf-r-" + id));
    }

    /** Publicação para réplicas autenticadas e subscritas. */
    public void publish(String topic, String message) {
        if (!topic.equals("tpf-ctl") && !topic.matches("tpf-r-[A-Za-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("Tópico inválido");
        for (var entry : sessions.entrySet()) {
            Session s = entry.getValue();
            if (s.authenticated && s.subs.contains(topic) && entry.getKey().isOpen())
                entry.getKey().send("PUB;" + topic + ";" + message);
        }
    }

    /** Modo isolado: distribui automaticamente chunks existentes na pasta selados. */
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("tpf.porto", 8765);
        final ChunkDispatcher[] dispatcher = new ChunkDispatcher[1];
        PubSubHub hub = new PubSubHub(port, Path.of("replicas.txt"), (replica, msg) -> {
            if (msg.startsWith("ACK;") || msg.startsWith("NACK;")) dispatcher[0].onReply(replica, msg);
            else if (!msg.startsWith("HB;")) System.out.println("Recebido de " + replica + ": " + msg);
        });
        dispatcher[0] = new ChunkDispatcher(hub, Path.of("selados"));
        hub.start();
        System.out.println("TOKEN PARA A (novo registo; 10 minutos): " + hub.gerarToken());
        System.out.println("TOKEN PARA B (novo registo; 10 minutos): " + hub.gerarToken());
        System.out.println("TOKEN PARA C (novo registo; 10 minutos): " + hub.gerarToken());
        System.out.println("Placement ponderado: R=3, W=2; réplicas A/B/C.");
        var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(() -> {
            try { dispatcher[0].dispatch(); }
            catch (Exception e) { System.err.println("Erro a distribuir: " + e); }
        }, 2, 3, java.util.concurrent.TimeUnit.SECONDS);
    }
}
