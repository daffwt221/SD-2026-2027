package rep02;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// API HTTP do Kubo: apenas biblioteca-padrão Java, sem guardar ficheiros no IPFS.
public class IpfsPubSub {
    private final String api;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public IpfsPubSub(String api) {
        this.api = api.replaceAll("/+$", "");
    }

    static String toMultibase(String text) {
        return "u" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static String fromMultibase(String text) {
        if (!text.startsWith("u")) throw new IllegalArgumentException("Multibase não suportado: " + text);
        return new String(Base64.getUrlDecoder().decode(text.substring(1)), StandardCharsets.UTF_8);
    }

    public void publish(String topic, String message) throws IOException, InterruptedException {
        String boundary = "rep01-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        // Bloqueia à espera da resposta do daemon; não confirma a escrita nas réplicas.
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("pub falhou (" + response.statusCode() + "): " + response.body());
        }
    }

    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("sub falhou (" + response.statusCode() + "): "
                        + lines.findFirst().orElse(""));
            }
            System.out.println("Subscrição ativa: " + topic);
            // A resposta HTTP mantém-se aberta: a leitura bloqueia entre mensagens.
            lines.forEach(json -> {
                String message;
                try {
                    message = fromMultibase(extractField(json, "data"));
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + json);
                    return;
                }
                onMessage.accept(message);
            });
        }
    }

    // Extração restrita aos campos base64url do Kubo, que não contêm escapes JSON.
    static String extractField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Campo ausente: " + field);
        return matcher.group(1);
    }
}
