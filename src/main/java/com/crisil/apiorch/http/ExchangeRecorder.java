package com.crisil.apiorch.http;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Captures one HTTP exchange for the attempt log (US-7.2), registered <b>outermost</b> on each endpoint's
 * {@code RestClient}, outside the auth interceptor, so it records only the final exchange. The response body is
 * wrapped in a counting tee that counts every byte read and keeps up to {@code maxBytes}; {@code responseTooLarge}
 * tells the executor to fail with {@code RESPONSE_TOO_LARGE} (US-4.3). Timing uses {@code System.nanoTime()}.
 * Call sites wrap the call in {@link #record}; the capture is per thread.
 */
public final class ExchangeRecorder implements ClientHttpRequestInterceptor {

    /** What was sent and received. Header values are raw here; masking is US-7.3's job. */
    public static final class Exchange {
        String method;
        String url;
        final Map<String, String> requestHeaders = new LinkedHashMap<>();
        String requestBody;
        long requestBytes;
        Integer status;
        final Map<String, String> responseHeaders = new LinkedHashMap<>();
        CountingTee tee;
        long startNanos;
        long endNanos;

        public String method() { return method; }
        public String url() { return url; }
        public Map<String, String> requestHeaders() { return requestHeaders; }
        public String requestBody() { return requestBody; }
        public long requestBytes() { return requestBytes; }
        public Integer status() { return status; }
        public Map<String, String> responseHeaders() { return responseHeaders; }

        public String responseBody() {
            return tee == null ? null : tee.kept();
        }

        public long responseBytes() {
            return tee == null ? 0 : tee.count;
        }

        public boolean responseTooLarge() {
            return tee != null && tee.overflow;
        }

        /** Request start to the end of reading the response, in milliseconds. */
        public long durationMs() {
            long end = tee != null && tee.endNanos > 0 ? tee.endNanos : endNanos;
            return end > 0 ? (end - startNanos) / 1_000_000 : 0;
        }
    }

    /** The result of {@link #record}: the call's value and the captured exchange (null if nothing was sent). */
    public record Recorded<T>(T value, Exchange exchange) {
    }

    private static final ThreadLocal<Exchange> CURRENT = new ThreadLocal<>();

    private final long maxBytes;

    public ExchangeRecorder(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Runs {@code call} and returns its value with the exchange it produced; rethrows the call's exception. */
    public static <T> Recorded<T> record(Supplier<T> call) {
        Exchange previous = CURRENT.get();
        CURRENT.remove();
        try {
            T value = call.get();
            return new Recorded<>(value, CURRENT.get());
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The exchange captured so far on this thread inside {@link #record}, for failed calls. */
    public static Exchange current() {
        return CURRENT.get();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Exchange exchange = new Exchange();
        exchange.method = request.getMethod().name();
        exchange.url = request.getURI().toString();
        request.getHeaders().forEach((name, values) -> exchange.requestHeaders.put(name, String.join(", ", values)));
        exchange.requestBytes = body.length;
        exchange.requestBody = body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
        exchange.startNanos = System.nanoTime();
        CURRENT.set(exchange);
        ClientHttpResponse response = execution.execute(request, body);
        exchange.endNanos = System.nanoTime();
        exchange.status = response.getStatusCode().value();
        response.getHeaders().forEach((name, values) -> exchange.responseHeaders.put(name, String.join(", ", values)));
        return new TeeResponse(response, exchange, maxBytes);
    }

    /** Counts every byte read and keeps up to {@code maxBytes} of them. */
    static final class CountingTee extends FilterInputStream {
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final long maxBytes;
        long count;
        boolean overflow;
        long endNanos;

        CountingTee(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                keep(new byte[] {(byte) b}, 0, 1);
            } else {
                endNanos = System.nanoTime();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                keep(buf, off, n);
            } else if (n < 0) {
                endNanos = System.nanoTime();
            }
            return n;
        }

        private void keep(byte[] buf, int off, int n) {
            count += n;
            long room = maxBytes - kept.size();
            if (room > 0) {
                kept.write(buf, off, (int) Math.min(room, n));
            }
            if (count > maxBytes) {
                overflow = true;
            }
        }

        String kept() {
            return kept.size() == 0 && count == 0 ? null : kept.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class TeeResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final Exchange exchange;
        private final long maxBytes;

        TeeResponse(ClientHttpResponse delegate, Exchange exchange, long maxBytes) {
            this.delegate = delegate;
            this.exchange = exchange;
            this.maxBytes = maxBytes;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (exchange.tee == null) {
                exchange.tee = new CountingTee(delegate.getBody(), maxBytes);
            }
            return exchange.tee;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
