package com.crisil.apiorch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/** US-1.1 AC12: typed bodies reject unknown properties; free-form bodies do not. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestEndpoints.class)
class StrictJsonBindingIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper objectMapper;

    @Test
    void bootObjectMapperFailsOnUnknownProperties() {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test
    void unknownPropertyOnATypedBodyIsRejectedByName() throws Exception {
        HttpResponse<String> res = new Http(port).postJson("/api/v1/admin/test/typed",
                "{\"name\":\"a\",\"dependOn\":\"x\"}");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("urn:apiorch:problem:validation-failed", "dependOn");
    }

    @Test
    void freeFormBodiesAcceptAnyProperty() throws Exception {
        HttpResponse<String> res = new Http(port).postJson("/api/v1/callbacks/r1/s1",
                "{\"anything\":1,\"password\":\"x\"}");
        assertThat(res.statusCode()).isEqualTo(200);
    }
}
