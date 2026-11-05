package com.crisil.apiorch.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.config.ApiorchProperties;

/**
 * Where apiorch may send requests (US-2.1 AC2): absolute {@code https} URLs (or {@code http} when
 * {@code apiorch.endpoints.allow-http}), no userinfo, query or fragment, a host on
 * {@code apiorch.endpoints.allowed-hosts}, and no IP-literal host outside the {@code local} profile. US-2.2 applies
 * the same policy to OAuth2 token URLs.
 */
@Component
public class HostPolicy {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final boolean allowHttp;
    private final List<String> allowedHosts;
    private final boolean local;

    @Autowired
    public HostPolicy(ApiorchProperties properties, Environment environment) {
        this(properties.endpoints().allowHttp(), properties.endpoints().allowedHosts(),
                environment.acceptsProfiles(Profiles.of("local")));
    }

    HostPolicy(boolean allowHttp, List<String> allowedHosts, boolean local) {
        this.allowHttp = allowHttp;
        this.allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        this.local = local;
    }

    /** Every reason {@code url} is not allowed; empty when it is. */
    public List<String> violations(String url) {
        List<String> problems = new ArrayList<>();
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            problems.add("is not a valid URL");
            return problems;
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            problems.add("must be an absolute URL with a host");
            return problems;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && allowHttp)) {
            problems.add(allowHttp ? "must use http or https" : "must use https");
        }
        if (uri.getRawUserInfo() != null) {
            problems.add("must not contain userinfo");
        }
        if (uri.getRawQuery() != null) {
            problems.add("must not contain a query");
        }
        if (uri.getRawFragment() != null) {
            problems.add("must not contain a fragment");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isIpLiteral(host) && !local) {
            problems.add("must not use an IP address outside the local profile");
        } else if (!isAllowed(host)) {
            problems.add("host '" + host + "' is not in apiorch.endpoints.allowed-hosts");
        }
        return problems;
    }

    boolean isAllowed(String host) {
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.")) {
                if (host.endsWith(allowed.substring(1)) && host.length() > allowed.length() - 1) {
                    return true;
                }
            } else if (allowed.equals(host)) {
                return true;
            }
        }
        return false;
    }

    static boolean isIpLiteral(String host) {
        return host.startsWith("[") || host.contains(":") || IPV4.matcher(host).matches();
    }
}
