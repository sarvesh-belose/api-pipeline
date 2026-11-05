package com.crisil.apiorch.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.observability.Problems;

/**
 * Per-path request body limits (US-1.1 AC5). A declared {@code Content-Length} over the limit is rejected before
 * the body is read; a chunked body is counted while it is read and fails as soon as it passes the limit. No Tomcat
 * or servlet property covers JSON bodies, hence this filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final Map<String, Long> limits = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(ApiorchProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        limits.put("/api/v1/admin/**", properties.definition().maxDocumentBytes());
        limits.put("/api/v1/pipelines/*/runs", properties.run().maxInputBytes());
        limits.put("/api/v1/callbacks/**", properties.callback().maxBodyBytes());
    }

    /** The limit for {@code path}, or {@code -1} when the path has none. */
    long limitFor(String path) {
        for (Map.Entry<String, Long> e : limits.entrySet()) {
            if (matcher.match(e.getKey(), path)) {
                return e.getValue();
            }
        }
        return -1;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request.getRequestURI().substring(request.getContextPath().length()));
        if (limit < 0) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            reject(request, response, limit);
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long limit) throws IOException {
        ProblemDetail problem = Problems.complete(Problems.of(HttpStatus.PAYLOAD_TOO_LARGE,
                ProblemTypes.PAYLOAD_TOO_LARGE, new PayloadTooLargeException(limit).getMessage()), request);
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Connection", "close");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    static final class CountingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) throws PayloadTooLargeException {
            count += n;
            if (count > limit) {
                throw new PayloadTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
