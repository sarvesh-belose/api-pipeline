package com.crisil.apiorch.config;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * US-1.4 AC4: {@code apiorch.callback.public-base-url} is handed to external systems, so outside {@code local} it
 * must be the environment's public address: not an IP literal, not {@code localhost}, and equal to
 * {@code apiorch.callback.expected-public-host} when that is set. A node address there makes every async
 * callback fail after a restart, silently. US-6.1 AC7 adds the {@code https} check and makes the URL required.
 */
@Component
public class PublicBaseUrlValidator implements ApplicationRunner {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final ApiorchProperties.Callback callback;
    private final boolean local;

    public PublicBaseUrlValidator(ApiorchProperties properties, Environment environment) {
        this.callback = properties.callback();
        this.local = environment.acceptsProfiles(Profiles.of("local"));
    }

    @Override
    public void run(ApplicationArguments args) {
        check(callback.publicBaseUrl(), callback.expectedPublicHost(), local);
    }

    static void check(String publicBaseUrl, String expectedHost, boolean local) {
        if (local || publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return;
        }
        String host;
        try {
            host = URI.create(publicBaseUrl).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null) {
            throw new IllegalStateException("apiorch.callback.public-base-url is not an absolute URL: " + publicBaseUrl);
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean nodeAddress = host.equals("localhost") || host.startsWith("[") || host.contains(":")
                || IPV4.matcher(host).matches();
        boolean unexpected = expectedHost != null && !expectedHost.isBlank()
                && !host.equals(expectedHost.strip().toLowerCase(Locale.ROOT));
        if (nodeAddress || unexpected) {
            throw new IllegalStateException("apiorch.callback.public-base-url must be the public load balancer address"
                    + (expectedHost != null && !expectedHost.isBlank() ? " (" + expectedHost + ")" : "")
                    + ", not '" + host + "'");
        }
    }
}
