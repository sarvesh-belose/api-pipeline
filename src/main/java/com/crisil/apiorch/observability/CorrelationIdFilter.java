package com.crisil.apiorch.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.crisil.apiorch.config.ApiorchProperties;

/**
 * Accepts or generates the correlation ID (US-1.3 AC1, AC2), stores it as a request attribute, puts it in MDC and
 * echoes it on the response. Runs first, and again on async dispatch, so every log line and problem carries it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "apiorch.correlationId";
    static final int MAX_LENGTH = 128;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final String header;

    public CorrelationIdFilter(ApiorchProperties properties) {
        this.header = properties.correlation().header();
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            id = resolve(request.getHeader(header));
            request.setAttribute(ATTRIBUTE, id);
            response.setHeader(header, id);
        }
        String previous = MDC.get(ProblemTypes.CORRELATION_ID);
        MDC.put(ProblemTypes.CORRELATION_ID, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(ProblemTypes.CORRELATION_ID);
            } else {
                MDC.put(ProblemTypes.CORRELATION_ID, previous);
            }
        }
    }

    static String resolve(String inbound) {
        if (inbound != null && VALID.matcher(inbound).matches()) {
            return inbound;
        }
        if (inbound != null) {
            log.debug("Replacing invalid inbound correlation ID (length {})", inbound.length());
        }
        return UUID.randomUUID().toString();
    }
}
