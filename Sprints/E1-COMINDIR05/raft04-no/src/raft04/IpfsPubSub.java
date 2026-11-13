package raft04;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.Stream;

/** Transporte HTTP do Kubo, sem confirmação de replicação e sem retransmissão das publicações. */
public final class IpfsPubSub implements AutoCloseable {
    private final String api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final CopyOnWriteArrayList<Stream<String>> subscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public IpfsPubSub(String api) {
        URI uri = URI.create(api);
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
            throw new IllegalArgumentException("API IPFS inválida");
        this.api = api.replaceAll("/+$", "");
    }

    static String toMultibase(String text) {
        return "u" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    static String fromMultibase(String text) {
        if (!text.startsWith("u")) throw new IllegalArgumentException("Multibase não suportado");
        return new String(Base64.getUrlDecoder().decode(text.substring(1)), StandardCharsets.UTF_8);
    }

    public void publish(String topic, String message) throws IOException, InterruptedException {
        if (closed) throw new IOException("transporte encerrado");
        String boundary = "raft04-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("pub " + response.statusCode() + ": " + response.body());
    }

    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        if (closed) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        Stream<String> lines = response.body();
        subscriptions.add(lines);
        try (Stream<String> stream = lines) {
            if (response.statusCode() != 200)
                throw new IOException("sub " + response.statusCode() + ": " + stream.findFirst().orElse(""));
            if (!closed) stream.forEach(json -> onMessage.accept(fromMultibase(extractField(json, "data"))));
        } finally {
            subscriptions.remove(lines);
        }
    }

    static String extractField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Campo ausente: " + field);
        return matcher.group(1);
    }

    @Override public void close() {
        closed = true;
        for (Stream<String> stream : subscriptions) {
            try { stream.close(); } catch (RuntimeException ignored) { }
        }
    }
}
