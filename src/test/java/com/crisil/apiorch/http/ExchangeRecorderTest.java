package com.crisil.apiorch.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The counting tee and the exchange's derived values, byte by byte and in bulk. */
class ExchangeRecorderTest {

    private static ExchangeRecorder.CountingTee tee(String body, long max) {
        return new ExchangeRecorder.CountingTee(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), max);
    }

    @Test
    void singleByteReadsAreCountedAndKept() throws IOException {
        ExchangeRecorder.CountingTee t = tee("abc", 2);
        while (t.read() >= 0) {
            // drain
        }
        assertThat(t.count).isEqualTo(3);
        assertThat(t.kept()).isEqualTo("ab");
        assertThat(t.overflow).isTrue();
        assertThat(t.endNanos).isPositive();
    }

    @Test
    void bulkReadsStopKeepingAtTheLimit() throws IOException {
        ExchangeRecorder.CountingTee t = tee("hello world", 100);
        byte[] buf = new byte[4];
        while (t.read(buf, 0, buf.length) >= 0) {
            // drain
        }
        assertThat(t.kept()).isEqualTo("hello world");
        assertThat(t.overflow).isFalse();
        assertThat(t.endNanos).isPositive();
    }

    @Test
    void emptyBodyKeepsNothing() throws IOException {
        ExchangeRecorder.CountingTee t = tee("", 10);
        assertThat(t.read(new byte[4], 0, 4)).isEqualTo(-1);
        assertThat(t.kept()).isNull();
    }

    @Test
    void exchangeWithoutAResponseBody() {
        ExchangeRecorder.Exchange e = new ExchangeRecorder.Exchange();
        assertThat(e.responseBody()).isNull();
        assertThat(e.responseBytes()).isZero();
        assertThat(e.responseTooLarge()).isFalse();
        assertThat(e.durationMs()).isZero();
        e.startNanos = 1_000_000;
        e.endNanos = 6_000_000;
        assertThat(e.durationMs()).isEqualTo(5);
        e.tee = tee("x", 10);
        assertThat(e.durationMs()).isEqualTo(5);
        e.tee.endNanos = 11_000_000;
        assertThat(e.durationMs()).isEqualTo(10);
    }
}
