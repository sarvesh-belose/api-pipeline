package com.crisil.apiorch.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-7.3 AC1–AC6 and the AC10 canary: a run whose input, headers, query and downstream responses carry canary values
 * reaches WireMock unmasked, while no captured log line and no {@code step_attempt} or {@code run_event} row holds any
 * canary. Bodies are logged too ({@code include-bodies-in-logs}), so the log path is exercised with payloads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "apiorch.logging.include-bodies-in-logs=true")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MaskingEndToEndIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final List<String> CANARIES = List.of("CANARY-PAN-0001", "CANARY-SECRET-0002", "CANARY-PAN-0003",
            "CANARY-TOKEN-0004", "CANARY-KEY-0005", "CANARY-CB-0006", "CANARY-COOKIE-0007", "CANARY-XML-0008",
            "CANARY-AADHAAR-0009");

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void noCanaryIsStoredOrLogged(CapturedOutput output) throws Exception {
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        api.stubFor(post(urlPathEqualTo("/kyc")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withHeader("Set-Cookie", "sid=CANARY-COOKIE-0007")
                .withBody("{\"ref\":\"K-1\",\"pan\":\"CANARY-PAN-0003\",\"kyc\":{\"aadhaar\":\"CANARY-AADHAAR-0009\"}}")));
        api.stubFor(get(urlPathEqualTo("/next/K-1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml").withBody("<r pan=\"CANARY-XML-0008\"/>")));

        String code = PublishedFixtures.uniqueCode("MASK");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.putObject("logging").put("capture", "FULL").putArray("maskKeys").add("pan").add("aadhaar")
                .add("applicants[*].pan");
        ObjectNode kyc = HttpPipelines.step(doc, "api1", endpoint, "POST", "/kyc");
        kyc.put("body", "{ 'name': input.name, 'pan': input.pan, 'applicants': input.applicants, "
                + "'password': input.password }");
        kyc.putObject("headers").put("Authorization", "'Bearer ' & input.token").put("X-Api-Key", "input.apiKey");
        kyc.putObject("query").put("t", "input.cb").put("pan", "input.pan");
        HttpPipelines.step(doc, "api2", endpoint, "GET", "/next/{steps.api1.body.ref}");
        PublishedFixtures.publish(jdbc, doc, 1);

        String input = """
                {"name":"Asha","pan":"CANARY-PAN-0001","password":"CANARY-SECRET-0002","token":"CANARY-TOKEN-0004",
                 "apiKey":"CANARY-KEY-0005","cb":"CANARY-CB-0006",
                 "applicants":[{"pan":"CANARY-PAN-0001"},{"pan":"CANARY-PAN-0001"}]}""";
        JsonNode run = json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", input).body());
        assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("COMPLETED");
        UUID runId = UUID.fromString(run.path("runId").asText());

        api.verify(postRequestedFor(urlPathEqualTo("/kyc"))
                .withQueryParam("t", equalTo("CANARY-CB-0006"))
                .withHeader("Authorization", equalTo("Bearer CANARY-TOKEN-0004"))
                .withRequestBody(containing("CANARY-PAN-0001")).withRequestBody(containing("CANARY-SECRET-0002")));

        List<Map<String, Object>> attempts = jdbc.sql("SELECT * FROM step_attempt WHERE run_id = :id ORDER BY event_seq")
                .param("id", runId).query().listOfRows();
        assertThat(attempts).hasSize(2);
        Map<String, Object> first = attempts.get(0);
        JsonNode stored = json.readTree((String) first.get("request_body"));
        assertThat(stored.path("name").asText()).isEqualTo("Asha");
        assertThat(stored.path("pan").asText()).isEqualTo("***");
        assertThat(stored.at("/applicants/1/pan").asText()).isEqualTo("***");
        assertThat(stored.path("password").asText()).isEqualTo("***");
        assertThat((String) first.get("url")).contains("t=***").contains("pan=***");
        assertThat((String) attempts.get(1).get("response_body"))
                .isEqualTo("<unparseable body: 26 bytes, text/xml>");

        List<String> eventDetails = jdbc.sql("SELECT details FROM run_event WHERE run_id = :id")
                .param("id", runId).query(String.class).list();
        for (String canary : CANARIES) {
            for (Map<String, Object> row : attempts) {
                row.values().forEach(v -> assertThat(String.valueOf(v)).as("step_attempt").doesNotContain(canary));
            }
            eventDetails.forEach(d -> assertThat(String.valueOf(d)).as("run_event").doesNotContain(canary));
            assertThat(output.getAll()).as("log output").doesNotContain(canary);
        }
        assertThat(output.getAll()).contains("attempt bodies step=api1");
    }
}
