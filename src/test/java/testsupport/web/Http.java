package testsupport.web;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

/** A tiny JDK HTTP client for integration tests against the real port. */
public final class Http {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;

    public Http(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path, String header, String value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header(header, value).GET().build(),
                BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, String json) throws Exception {
        return send("POST", path, json, null);
    }

    public HttpResponse<String> putJson(String path, String json, String ifMatch) throws Exception {
        return send("PUT", path, json, ifMatch);
    }

    public HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(), BodyHandlers.ofString());
    }

    public HttpResponse<String> send(String method, String path, String json, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** POST without a Content-Length, so the body is sent chunked. */
    public HttpResponse<String> postChunked(String path, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).build(),
                BodyHandlers.ofString());
    }

    /** A JSON object whose serialised form is at least {@code bytes} long. */
    public static String jsonOfSize(int bytes) {
        return "{\"pad\":\"" + "x".repeat(Math.max(0, bytes - 10)) + "\"}";
    }
}
