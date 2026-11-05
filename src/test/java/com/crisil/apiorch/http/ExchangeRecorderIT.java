package com.crisil.apiorch.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.http.auth.AuthInterceptor;
import com.crisil.apiorch.http.auth.OAuth2TokenCache;

/** US-7.2 groundwork: what the outermost recorder captures, including true sizes past the kept limit. */
class ExchangeRecorderIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private RestClient client(long maxBytes) {
        return RestClient.builder().baseUrl("http://localhost:" + api.getPort())
                .requestInterceptor(new ExchangeRecorder(maxBytes)).build();
    }

    @Test
    void capturesRequestResponseSizesAndTiming() {
        api.stubFor(post("/kyc").willReturn(aResponse().withStatus(201).withHeader("X-Ref", "r-1")
                .withHeader("Content-Type", "application/json").withBody("{\"ref\":\"K-9\"}")));
        ExchangeRecorder.Recorded<String> r = ExchangeRecorder.record(() -> client(1024).post().uri("/kyc")
                .contentType(MediaType.APPLICATION_JSON).body("{\"customerId\":\"C-77\"}").retrieve().body(String.class));
        assertThat(r.value()).isEqualTo("{\"ref\":\"K-9\"}");
        ExchangeRecorder.Exchange e = r.exchange();
        assertThat(e.method()).isEqualTo("POST");
        assertThat(e.url()).endsWith("/kyc");
        assertThat(e.requestBody()).isEqualTo("{\"customerId\":\"C-77\"}");
        assertThat(e.requestBytes()).isEqualTo(21);
        assertThat(e.requestHeaders()).containsKey("Content-Type");
        assertThat(e.status()).isEqualTo(201);
        assertThat(e.responseHeaders()).containsEntry("x-ref", "r-1");
        assertThat(e.responseBody()).isEqualTo("{\"ref\":\"K-9\"}");
        assertThat(e.responseBytes()).isEqualTo(13);
        assertThat(e.responseTooLarge()).isFalse();
        assertThat(e.durationMs()).isNotNegative();
    }

    @Test
    void responseOverTheLimitIsCountedButNotKept() {
        api.stubFor(get("/big").willReturn(aResponse().withStatus(200).withBody("x".repeat(5000))));
        ExchangeRecorder.Recorded<String> r = ExchangeRecorder.record(() -> client(100).get().uri("/big").retrieve()
                .body(String.class));
        assertThat(r.exchange().responseBytes()).isEqualTo(5000);
        assertThat(r.exchange().responseBody()).hasSize(100);
        assertThat(r.exchange().responseTooLarge()).isTrue();
        assertThat(r.exchange().requestBody()).isNull();
    }

    @Test
    void nothingSentMeansNoExchangeAndFailuresKeepWhatWasCaptured() {
        assertThat(ExchangeRecorder.record(() -> "no call").exchange()).isNull();
        api.stubFor(get("/err").willReturn(aResponse().withStatus(500).withBody("boom")));
        assertThatThrownBy(() -> ExchangeRecorder.record(() -> {
            try {
                return client(1024).get().uri("/err").retrieve().body(String.class);
            } finally {
                assertThat(ExchangeRecorder.current().status()).isEqualTo(500);
            }
        })).hasMessageContaining("500");
        assertThat(ExchangeRecorder.current()).isNull();
    }

    @Test
    void recorderOutsideAuthSeesTheFinalHeaders() {
        api.stubFor(get("/x").willReturn(aResponse().withStatus(200)));
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + api.getPort())
                .requestInterceptor(new ExchangeRecorder(1024))
                .requestInterceptor(new AuthInterceptor("e", new EndpointRequest.Auth(EndpointRequest.AuthType.API_KEY,
                        "X-Api-Key", "env:APIORCH_SECRET_K", null, null, null, null, null, null, null, null, null, null),
                        ref -> Optional.of("k"), new OAuth2TokenCache(ref -> Optional.empty(), RestClient.builder(),
                        java.time.Clock.systemUTC())))
                .build();
        ExchangeRecorder.Recorded<Integer> r = ExchangeRecorder.record(() -> client.get().uri("/x")
                .exchange((req, res) -> res.getStatusCode().value()));
        assertThat(r.value()).isEqualTo(200);
        assertThat(r.exchange().responseBody()).isNull();
    }
}
