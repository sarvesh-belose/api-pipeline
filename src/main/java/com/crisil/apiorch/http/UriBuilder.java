package com.crisil.apiorch.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds a request URI from an endpoint base URL and path segments, never by string concatenation (US-2.1). The
 * result keeps the base URL's scheme, host and port and starts with every base path segment, compared segment by
 * segment, so a segment such as {@code ..} or an encoded slash can never escape the endpoint.
 */
public final class UriBuilder {

    private UriBuilder() {
    }

    /** {@code path} is split on {@code /}; each part becomes one encoded segment. */
    public static URI build(String baseUrl, String path) {
        List<String> segments = new ArrayList<>();
        if (path != null) {
            for (String part : path.split("/")) {
                if (!part.isEmpty()) {
                    segments.add(part);
                }
            }
        }
        return build(baseUrl, segments);
    }

    public static URI build(String baseUrl, List<String> segments) {
        return build(baseUrl, segments, Map.of());
    }

    /** As {@link #build(String, List)}, with query parameters in {@code query}'s order, each value encoded. */
    public static URI build(String baseUrl, List<String> segments, Map<String, String> query) {
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Path segment '" + segment + "' is not allowed");
            }
        }
        URI base = URI.create(baseUrl);
        // values go in as template variables, so a brace in a value is data and every value is strictly encoded
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl);
        Map<String, String> variables = new HashMap<>();
        for (String segment : segments) {
            String name = "v" + variables.size();
            variables.put(name, segment);
            builder.pathSegment("{" + name + "}");
        }
        for (Map.Entry<String, String> param : query.entrySet()) {
            String name = "v" + variables.size();
            variables.put(name, param.getValue());
            builder.queryParam(param.getKey(), "{" + name + "}");
        }
        URI built = builder.encode().buildAndExpand(variables).toUri();
        verify(base, built);
        return built;
    }

    private static void verify(URI base, URI built) {
        if (!base.getScheme().equalsIgnoreCase(built.getScheme())
                || !base.getHost().equalsIgnoreCase(built.getHost())
                || base.getPort() != built.getPort()) {
            throw new IllegalArgumentException("Built URI leaves the endpoint's origin");
        }
        List<String> baseSegments = segments(base.getRawPath());
        List<String> builtSegments = segments(built.getRawPath());
        if (builtSegments.size() < baseSegments.size()
                || !builtSegments.subList(0, baseSegments.size()).equals(baseSegments)) {
            throw new IllegalArgumentException("Built URI leaves the endpoint's base path");
        }
    }

    private static List<String> segments(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(rawPath.split("/")).filter(s -> !s.isEmpty()).toList();
    }
}
