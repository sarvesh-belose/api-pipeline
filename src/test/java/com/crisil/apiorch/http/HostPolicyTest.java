package com.crisil.apiorch.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class HostPolicyTest {

    private final HostPolicy strict = new HostPolicy(false, List.of("kyc.internal.example.com", "*.svc.example.com"), false);
    private final HostPolicy local = new HostPolicy(true, List.of("localhost", "127.0.0.1"), true);

    @Test
    void allowedHttpsHostsPass() {
        assertThat(strict.violations("https://kyc.internal.example.com/api")).isEmpty();
        assertThat(strict.violations("https://a.svc.example.com")).isEmpty();
        assertThat(strict.violations("https://A.SVC.EXAMPLE.COM/x")).isEmpty();
    }

    @Test
    void wildcardNeedsASubdomain() {
        assertThat(strict.violations("https://svc.example.com")).anyMatch(m -> m.contains("allowed-hosts"));
        assertThat(strict.violations("https://evilsvc.example.com")).anyMatch(m -> m.contains("allowed-hosts"));
    }

    @Test
    void schemeUserinfoQueryAndFragmentAreChecked() {
        assertThat(strict.violations("http://kyc.internal.example.com")).containsExactly("must use https");
        assertThat(local.violations("ftp://localhost")).containsExactly("must use http or https");
        assertThat(strict.violations("https://u:p@kyc.internal.example.com/x?a=1#f"))
                .contains("must not contain userinfo", "must not contain a query", "must not contain a fragment");
    }

    @Test
    void relativeAndMalformedUrlsFail() {
        assertThat(strict.violations("/relative/path")).containsExactly("must be an absolute URL with a host");
        assertThat(strict.violations("https://bad host")).containsExactly("is not a valid URL");
    }

    @Test
    void ipLiteralsOnlyInLocal() {
        assertThat(strict.violations("https://10.0.0.1")).anyMatch(m -> m.contains("IP address"));
        assertThat(strict.violations("https://[::1]:8443")).anyMatch(m -> m.contains("IP address"));
        assertThat(local.violations("http://127.0.0.1:8080")).isEmpty();
        assertThat(local.violations("http://10.0.0.1")).anyMatch(m -> m.contains("allowed-hosts"));
        assertThat(local.violations("http://localhost:8080/x")).isEmpty();
    }
}
