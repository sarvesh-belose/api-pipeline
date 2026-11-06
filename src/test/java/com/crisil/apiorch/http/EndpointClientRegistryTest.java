package com.crisil.apiorch.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.endpoint.EndpointChangedEvent;
import com.crisil.apiorch.endpoint.EndpointConfig;
import com.crisil.apiorch.endpoint.EndpointEntity;
import com.crisil.apiorch.endpoint.EndpointMapper;
import com.crisil.apiorch.endpoint.EndpointRepository;
import com.crisil.apiorch.endpoint.EndpointRepository.NameAndVersion;

/** The registry's cache: lazy load, rebuild on change, refresh by row version, and removal. */
class EndpointClientRegistryTest {

    private final EndpointRepository repository = mock(EndpointRepository.class);
    private final ApiorchProperties properties = new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource())
            .bindOrCreate("apiorch", ApiorchProperties.class);
    private final EndpointMapper mapper = new EndpointMapper(new ObjectMapper(), properties);
    private final EndpointClientRegistry registry = new EndpointClientRegistry(repository, mapper, RestClient.builder(),
            mock(com.crisil.apiorch.http.auth.SecretResolver.class), mock(com.crisil.apiorch.http.auth.OAuth2TokenCache.class),
            properties, java.time.Clock.systemUTC(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final List<NameAndVersion> versions = new ArrayList<>();

    private EndpointEntity entity(String name, String baseUrl, String httpVersion) {
        EndpointEntity e = new EndpointEntity(UUID.randomUUID(), name, Instant.EPOCH, "t");
        e.setBaseUrl(baseUrl);
        e.setConnectTimeoutMs(1000);
        e.setReadTimeoutMs(2000);
        e.setHttpVersion(httpVersion);
        e.setAuthType("NONE");
        e.setEnabled(true);
        return e;
    }

    private static NameAndVersion version(String name, long v) {
        return new NameAndVersion() {
            public String getName() { return name; }
            public long getRowVersion() { return v; }
        };
    }

    @Test
    void loadsLazilyAndCaches() {
        when(repository.findByName("a")).thenReturn(Optional.of(entity("a", "https://a.example.com", "HTTP_2")));
        EndpointRuntime first = registry.get("a").orElseThrow();
        assertThat(first.httpClient().version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_2);
        assertThat(registry.get("a").orElseThrow()).isSameAs(first);
        when(repository.findByName("missing")).thenReturn(Optional.empty());
        assertThat(registry.get("missing")).isEmpty();
    }

    @Test
    void changeEventRebuildsAndRemoves() {
        when(repository.findByName("a")).thenReturn(Optional.of(entity("a", "https://a.example.com", "HTTP_1_1")));
        EndpointRuntime first = registry.get("a").orElseThrow();
        when(repository.findByName("a")).thenReturn(Optional.of(entity("a", "https://b.example.com", "HTTP_1_1")));
        registry.onChange(new EndpointChangedEvent("a"));
        assertThat(registry.get("a").orElseThrow()).isNotSameAs(first);
        assertThat(registry.get("a").orElseThrow().config().baseUrl()).isEqualTo("https://b.example.com");
        when(repository.findByName("a")).thenReturn(Optional.empty());
        registry.onChange(new EndpointChangedEvent("a"));
        assertThat(registry.get("a")).isEmpty();
    }

    @Test
    void refreshRebuildsChangedVersionsAndDropsDeletedOnes() {
        when(repository.findByName("a")).thenReturn(Optional.of(entity("a", "https://a.example.com", "HTTP_1_1")));
        when(repository.findByName("b")).thenReturn(Optional.of(entity("b", "https://b.example.com", "HTTP_1_1")));
        EndpointRuntime a = registry.get("a").orElseThrow();
        EndpointRuntime b = registry.get("b").orElseThrow();
        when(repository.findAllVersions()).thenReturn(List.of(version("a", 0), version("c", 4)));
        registry.refresh();
        assertThat(registry.get("a").orElseThrow()).isSameAs(a);
        when(repository.findByName("b")).thenReturn(Optional.empty());
        assertThat(registry.get("b")).isEmpty();

        when(repository.findAllVersions()).thenReturn(List.of(version("a", 7)));
        registry.refresh();
        assertThat(registry.get("a").orElseThrow()).isNotSameAs(a);
        assertThat(b).isNotNull();
    }

    @Test
    void refreshSurvivesADatabaseError() {
        when(repository.findAllVersions()).thenThrow(new IllegalStateException("db down"));
        registry.refresh();
    }

    @Test
    void eachEndpointGetsItsOwnClient() {
        EndpointConfig one = mapper.toConfig(entity("one", "https://a.example.com", "HTTP_1_1"));
        EndpointConfig two = mapper.toConfig(entity("two", "https://b.example.com", "HTTP_1_1"));
        assertThat(registry.build(one).restClient()).isNotSameAs(registry.build(two).restClient());
        assertThat(registry.build(one).name()).isEqualTo("one");
    }
}
