package com.crisil.apiorch.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class UriBuilderTest {

    @Test
    void appendsSegmentsToTheBasePath() {
        assertThat(UriBuilder.build("https://kyc.example.com/api", "/v1/customers"))
                .isEqualTo(URI.create("https://kyc.example.com/api/v1/customers"));
        assertThat(UriBuilder.build("https://kyc.example.com:8443", "/health"))
                .isEqualTo(URI.create("https://kyc.example.com:8443/health"));
        assertThat(UriBuilder.build("https://kyc.example.com/api/", (String) null))
                .isEqualTo(URI.create("https://kyc.example.com/api/"));
    }

    @Test
    void encodesEachSegment() {
        URI uri = UriBuilder.build("https://kyc.example.com/api", List.of("customers", "a b/c?d"));
        assertThat(uri.getRawPath()).isEqualTo("/api/customers/a%20b%2Fc%3Fd");
        assertThat(uri.getRawQuery()).isNull();
    }

    @Test
    void rejectsDotAndEmptySegments() {
        assertThatThrownBy(() -> UriBuilder.build("https://a.example.com/api", List.of(".."))).hasMessageContaining("..");
        assertThatThrownBy(() -> UriBuilder.build("https://a.example.com/api", List.of("."))).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> UriBuilder.build("https://a.example.com/api", List.of(""))).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> UriBuilder.build("https://a.example.com/api", "/x/../../admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentWithAnAtSignCannotChangeTheHost() {
        URI uri = UriBuilder.build("https://a.example.com/api", List.of("@evil.com"));
        assertThat(uri.getHost()).isEqualTo("a.example.com");
    }
}
