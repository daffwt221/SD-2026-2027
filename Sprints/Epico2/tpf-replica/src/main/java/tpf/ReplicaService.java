package tpf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Cliente WebSocket da réplica: registo Ed25519, desafio, SUB, HB e reconexão. */
public final class ReplicaService implements WebSocket.Listener {
    private final String id, url, registrationToken;
    private final long quota;
    private final KeyPair keys;
    private final Path directory;
    private final ReplicaStorage storage;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile WebSocket ws;
    private volatile boolean authenticated;
    private final StringBuilder fragments = new StringBuilder();
    private volatile CountDownLatch disconnected = new CountDownLatch(1);

    public ReplicaService(String id, String url, long quota, String token) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{1,40}") || quota < 0) throw new IllegalArgumentException("ID ou quota inválidos");
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) throw new IllegalArgumentException("URL WebSocket inválido");
        this.id = id; this.url = url; this.quota = quota; this.registrationToken = token;
        this.directory = Path.of("dados-" + id);
        Files.createDirectories(directory);
        storage = new ReplicaStorage(directory, quota);
        keys = loadOrCreateKeys(directory);
        timer.scheduleAtFixedRate(this::heartbeat, 2, 2, TimeUnit.SECONDS);
    }

    private static KeyPair loadOrCreateKeys(Path folder) throws Exception {
        Path priv = folder.resolve("chave.der");
        Path pub = folder.resolve("chave-publica.der");
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        if (Files.exists(priv) != Files.exists(pub)) throw new IOException("Par de chaves incompleto");
        if (Files.exists(priv)) return new KeyPair(
            factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub))),
            factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv))));
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.write(priv, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
        Files.write(pub, pair.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
        return pair;
    }

    public void run() throws Exception {
        int backoff = 1;
        HttpClient client = HttpClient.newHttpClient();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                authenticated = false;
                disconnected = new CountDownLatch(1);
                try {
                    ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(url), this).join();
                    backoff = 1;
                    disconnected.await();
                } catch (Exception e) { System.err.println("Ligação falhou: " + e.getMessage()); }
                authenticated = false;
                System.out.println("Reconectar em " + backoff + "s...");
                Thread.sleep(backoff * 1000L);
                backoff = Math.min(30, backoff * 2);
            }
        } finally { timer.shutdownNow(); }
    }

    @Override public void onOpen(WebSocket socket) {
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        String hello = "OLA;" + id + ";" + pub + ";1" + (registrationToken == null ? "" : ";" + registrationToken);
        socket.sendText(hello, true);
        socket.request(1);
    }
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        try {
            fragments.append(data);
            if (last) {
                String message = fragments.toString(); fragments.setLength(0);
                receive(socket, message);
            }
        } catch (Exception e) {
            System.err.println("Erro ao processar mensagem: " + e.getMessage());
            socket.abort(); disconnected.countDown();
        }
        socket.request(1);
        return null;
    }
    private void receive(WebSocket socket, String message) throws Exception {
        if (message.startsWith("DESAFIO;")) {
            byte[] nonce = Base64.getDecoder().decode(message.substring("DESAFIO;".length()));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.getPrivate()); signature.update(nonce);
            socket.sendText("PROVA;" + id + ";" + Base64.getEncoder().encodeToString(signature.sign()), true);
        } else if (message.equals("AUTENTICADO;" + id)) {
            authenticated = true;
            System.out.println("AUTENTICADO: " + id);
            socket.sendText("SUB;tpf-r-" + id, true);
            socket.sendText("SUB;tpf-ctl", true);
            publish("INVENTARIO;" + id + ";" + System.nanoTime() + ";0;");
        } else if (message.startsWith("SUBOK;")) {
            System.out.println(message);
        } else if (message.startsWith("PUB;tpf-r-" + id + ";STORE;")) {
            String[] f = message.split(";", 6);
            if (f.length != 6) throw new IOException("STORE incompleto");
            String response;
            try { response = storage.store(f[3], f[4], f[5]); }
            catch (Exception ex) { response = "NACK;" + f[3] + ";FALHA_ARMAZENAMENTO"; System.err.println(ex); }
            publish(response);
            System.out.println(response);
        } else if (message.startsWith("PUB;")) {
            System.out.println("Recebido do hub: " + message);
        } else {
            System.out.println("Mensagem do hub: " + message);
        }
    }
    private void publish(String payload) {
        WebSocket current = ws;
        if (authenticated && current != null && !current.isOutputClosed())
            current.sendText("PUB;tpf-lider;" + payload, true)
                .exceptionally(err -> {System.err.println("Falha PUB: " + err.getMessage()); return null;});
    }
    private void heartbeat() {
        try {
            if (!authenticated) return;
            long used = storage.bytesUsed();
            publish("HB;" + id + ";" + System.currentTimeMillis() + ";" + quota + ";" + used + ";0;0");
        } catch (Exception e) { System.err.println("Heartbeat falhou: " + e.getMessage()); }
    }
    @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
        System.out.println("Hub fechou a ligação: " + status + " " + reason);
        authenticated = false; disconnected.countDown(); return null;
    }
    @Override public void onError(WebSocket socket, Throwable error) {
        System.err.println("WebSocket: " + error.getMessage());
        authenticated = false; disconnected.countDown();
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Uso: ReplicaService <id> <url> <quotaBytes> [token]"); return;
        }
        new ReplicaService(args[0], args[1], Long.parseLong(args[2]), args.length == 4 ? args[3] : null).run();
    }
}
