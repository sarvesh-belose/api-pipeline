package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.crisil.apiorch.config.ApiorchProperties;

/** US-1.3 AC1, AC2: the correlation ID is kept when valid, replaced when missing or invalid, and MDC is cleared. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(new ApiorchProperties(
            new ApiorchProperties.Correlation("X-Correlation-Id"), null, null, null, null, null, null, null));

    private String run(String inbound, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        if (inbound != null) {
            request.addHeader("X-Correlation-Id", inbound);
        }
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get("correlationId"));
            }
        });
        assertThat(request.getAttribute(CorrelationIdFilter.ATTRIBUTE)).isEqualTo(seen.get());
        assertThat(MDC.get("correlationId")).isNull();
        return seen.get();
    }

    @Test
    void validInboundIdIsKeptAndEchoed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run("abc-123", response)).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
    }

    @Test
    void missingIdIsGeneratedAsUuid() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String id = run(null, response);
        assertThat(UUID.fromString(id).version()).isEqualTo(4);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(id);
    }

    @Test
    void invalidIdsAreReplaced() throws Exception {
        assertThat(run("bad value!", new MockHttpServletResponse())).isNotEqualTo("bad value!");
        String tooLong = "a".repeat(CorrelationIdFilter.MAX_LENGTH + 1);
        assertThat(run(tooLong, new MockHttpServletResponse())).hasSize(36);
        assertThat(run("a".repeat(CorrelationIdFilter.MAX_LENGTH), new MockHttpServletResponse()))
                .hasSize(CorrelationIdFilter.MAX_LENGTH);
    }

    @Test
    void outerMdcValueIsRestored() throws Exception {
        MDC.put("correlationId", "outer");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.addHeader("X-Correlation-Id", "inner");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(MDC.get("correlationId")).isEqualTo("outer");
        } finally {
            MDC.clear();
        }
    }
}
