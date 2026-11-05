package testsupport.web;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import testsupport.security.TestJwt;

/**
 * A tiny JDK HTTP client for integration tests against the real port. By default it sends a test JWT with all
 * three roles (US-1.2); {@link #as(String)} switches identity and {@link #anonymous(int)} sends none.
 */
public final class Http {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;
    private final String token;

    public Http(int port) {
        this(port, TestJwt.allRoles());
    }

    private Http(int port, String token) {
        this.base = "http://localhost:" + port;
        this.token = token;
    }

    public static Http anonymous(int port) {
        return new Http(port, null);
    }

    /** The same server with another bearer token. */
    public Http as(String bearerToken) {
        return new Http(Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)), bearerToken);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path, String header, String value) throws Exception {
        return client.send(request(path).header(header, value).GET().build(), BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, String json) throws Exception {
        return send("POST", path, json, null);
    }

    public HttpResponse<String> putJson(String path, String json, String ifMatch) throws Exception {
        return send("PUT", path, json, ifMatch);
    }

    public HttpResponse<String> delete(String path) throws Exception {
        return client.send(request(path).DELETE().build(), BodyHandlers.ofString());
    }

    public HttpResponse<String> send(String method, String path, String json, String ifMatch) throws Exception {
        return send(method, path, json, ifMatch, null, null);
    }

    /** As {@link #send(String, String, String, String)}, with one extra request header when {@code header} is set. */
    public HttpResponse<String> send(String method, String path, String json, String ifMatch, String header,
            String value) throws Exception {
        HttpRequest.Builder b = request(path)
                .header("Content-Type", "application/json")
                .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        if (header != null) {
            b.header(header, value);
        }
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** POST without a Content-Length, so the body is sent chunked. */
    public HttpResponse<String> postChunked(String path, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return client.send(request(path)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).build(),
                BodyHandlers.ofString());
    }

    /** A JSON object whose serialised form is at least {@code bytes} long. */
    public static String jsonOfSize(int bytes) {
        return "{\"pad\":\"" + "x".repeat(Math.max(0, bytes - 10)) + "\"}";
    }
}
