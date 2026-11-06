package com.crisil.apiorch.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** US-5.1: attempt timeouts round up to 250 ms buckets, capped at 120 s, one cached client per bucket. */
class TimeoutBucketTest {

    @Test
    void bucketsRoundUpAndCap() {
        assertThat(EndpointRuntime.bucket(1)).isEqualTo(250);
        assertThat(EndpointRuntime.bucket(250)).isEqualTo(250);
        assertThat(EndpointRuntime.bucket(251)).isEqualTo(500);
        assertThat(EndpointRuntime.bucket(1999)).isEqualTo(2000);
        assertThat(EndpointRuntime.bucket(0)).isEqualTo(250);
        assertThat(EndpointRuntime.bucket(600_000)).isEqualTo(120_000);
    }

    @Test
    void oneClientPerBucket() {
        EndpointRuntime runtime = new EndpointRuntime(null, RestClient.builder().build(), HttpClient.newHttpClient());
        RestClient a = runtime.restClient(1900);
        assertThat(runtime.restClient(2000)).isSameAs(a);
        assertThat(runtime.restClient(2001)).isNotSameAs(a);
        assertThat(runtime.buckets()).hasSize(2);
    }
}
