package com.crisil.apiorch.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** US-1.4 AC4: node addresses and unexpected hosts fail startup outside local. */
class PublicBaseUrlGuardTest {

    @Test
    void nodeAddressesAreRejectedOutsideLocal() {
        for (String url : new String[] {"https://10.0.0.5:8080", "https://localhost/api", "https://[::1]:8443",
                "not a url", "/relative"}) {
            assertThatThrownBy(() -> PublicBaseUrlValidator.check(url, null, false)).as(url)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void hostMustMatchTheExpectedPublicHost() {
        assertThatThrownBy(() -> PublicBaseUrlValidator.check("https://apiorch.other.example.com", "apiorch.test.example.com",
                false)).hasMessageContaining("apiorch.test.example.com");
        assertThatCode(() -> PublicBaseUrlValidator.check("https://APIORCH.test.example.com", "apiorch.test.example.com",
                false)).doesNotThrowAnyException();
        assertThatCode(() -> PublicBaseUrlValidator.check("https://apiorch.test.example.com", " ", false))
                .doesNotThrowAnyException();
    }

    @Test
    void localAndUnsetAreNotChecked() {
        assertThatCode(() -> PublicBaseUrlValidator.check("http://localhost:8080", null, true)).doesNotThrowAnyException();
        assertThatCode(() -> PublicBaseUrlValidator.check(null, "x", false)).doesNotThrowAnyException();
        assertThatCode(() -> PublicBaseUrlValidator.check("", "x", false)).doesNotThrowAnyException();
    }
}
