package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemTypesTest {

    @Test
    void frameworkStatusesMapToSlugs() {
        assertThat(ProblemTypes.forStatus(HttpStatus.BAD_REQUEST)).isEqualTo("validation-failed");
        assertThat(ProblemTypes.forStatus(HttpStatus.NOT_FOUND)).isEqualTo("not-found");
        assertThat(ProblemTypes.forStatus(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo("method-not-allowed");
        assertThat(ProblemTypes.forStatus(HttpStatus.NOT_ACCEPTABLE)).isEqualTo("not-acceptable");
        assertThat(ProblemTypes.forStatus(HttpStatus.PAYLOAD_TOO_LARGE)).isEqualTo("payload-too-large");
        assertThat(ProblemTypes.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)).isEqualTo("unsupported-media-type");
        assertThat(ProblemTypes.forStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo("shutting-down");
        assertThat(ProblemTypes.forStatus(HttpStatus.CONFLICT)).isEqualTo("validation-failed");
        assertThat(ProblemTypes.forStatus(HttpStatus.BAD_GATEWAY)).isEqualTo("internal-error");
    }

    @Test
    void completeKeepsWhatIsSetAndFillsTheRest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, "c-1");
        ProblemDetail blank = Problems.complete(ProblemDetail.forStatus(HttpStatus.NOT_FOUND), request);
        assertThat(blank.getType()).isEqualTo(URI.create("urn:apiorch:problem:not-found"));
        assertThat(blank.getTitle()).isEqualTo("Not Found");
        assertThat(blank.getInstance()).isEqualTo(URI.create("/api/v1/x"));
        assertThat(blank.getProperties()).containsEntry("correlationId", "c-1");

        ProblemDetail set = Problems.of(HttpStatus.CONFLICT, "already-exists", "d");
        set.setTitle("Custom");
        Problems.complete(set, null);
        assertThat(set.getType()).isEqualTo(URI.create("urn:apiorch:problem:already-exists"));
        assertThat(set.getTitle()).isEqualTo("Custom");
        assertThat(set.getInstance()).isNull();

        ProblemDetail odd = Problems.complete(ProblemDetail.forStatus(HttpStatusCode.valueOf(499)), null);
        assertThat(odd.getTitle()).isEqualTo("Error");
    }
}
