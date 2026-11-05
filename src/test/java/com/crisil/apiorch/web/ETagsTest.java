package com.crisil.apiorch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.crisil.apiorch.observability.ApiProblemException;

class ETagsTest {

    @Test
    void roundTripsVersions() {
        assertThat(ETags.of(3)).isEqualTo("\"3\"");
        assertThat(ETags.parse("\"3\"")).isEqualTo(3L);
        assertThat(ETags.parse("W/\"4\"")).isEqualTo(4L);
        assertThat(ETags.parse(" 5 ")).isEqualTo(5L);
        assertThat(ETags.parse(null)).isNull();
        assertThat(ETags.parse("  ")).isNull();
        assertThatThrownBy(() -> ETags.parse("\"abc\"")).isInstanceOf(ApiProblemException.class);
    }
}
