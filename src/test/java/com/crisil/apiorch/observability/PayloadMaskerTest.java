package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** US-7.3: keys at depth, case, {@code [*]} paths, arrays, forms, URLs, headers, placeholders, and the log net. */
class PayloadMaskerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MaskingPolicy policy = MaskingPolicy.of(List.of("Authorization", "X-Api-Key"),
            List.of("pan", "aadhaar", "applicants[*].pan", "customer.dob", "password"));

    private String mask(String body) throws Exception {
        return json.writeValueAsString(PayloadMasker.maskJson(json.readTree(body), policy));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"name\":\"Asha\",\"pan\":\"ABCDE1234F\",\"kyc\":{\"aadhaar\":\"1234\"}}|"
                    + "{\"name\":\"Asha\",\"pan\":\"***\",\"kyc\":{\"aadhaar\":\"***\"}}",
            "{\"PAN\":\"x\",\"Kyc\":{\"AadHaar\":{\"n\":1}}}|{\"PAN\":\"***\",\"Kyc\":{\"AadHaar\":\"***\"}}",
            "{\"customer\":{\"dob\":\"1990\",\"name\":\"A\"},\"dob\":\"keep\"}|"
                    + "{\"customer\":{\"dob\":\"***\",\"name\":\"A\"},\"dob\":\"keep\"}",
            "{\"list\":[{\"pan\":\"a\"},{\"pan\":\"b\"}],\"n\":[1,2]}|{\"list\":[{\"pan\":\"***\"},{\"pan\":\"***\"}],\"n\":[1,2]}",
            "[{\"password\":\"p\"}]|[{\"password\":\"***\"}]",
            "\"just a string\"|\"just a string\""})
    void jsonIsMaskedByKeyAtAnyDepthAndByPathFromTheRoot(String in, String out) throws Exception {
        assertThat(mask(in)).isEqualTo(out);
    }

    @Test
    void pathRuleDoesNotMaskTheSameKeyElsewhere() throws Exception {
        MaskingPolicy pathOnly = MaskingPolicy.of(List.of(), List.of("applicants[*].pan", "matrix[*][*]"));
        JsonNode masked = PayloadMasker.maskJson(json.readTree(
                "{\"pan\":\"top\",\"applicants\":[{\"pan\":\"a\"},{\"pan\":\"b\"}],\"matrix\":[[1,2]]}"), pathOnly);
        assertThat(masked.path("pan").asText()).isEqualTo("top");
        assertThat(masked.at("/applicants/0/pan").asText()).isEqualTo("***");
        assertThat(masked.at("/applicants/1/pan").asText()).isEqualTo("***");
        assertThat(masked.at("/matrix/0/1").asText()).isEqualTo("***");
    }

    @Test
    void inputIsNeverMutated() throws Exception {
        JsonNode original = json.readTree("{\"pan\":\"x\"}");
        PayloadMasker.maskJson(original, policy);
        assertThat(original.path("pan").asText()).isEqualTo("x");
    }

    @Test
    void headersUrlsAndForms() {
        assertThat(PayloadMasker.maskHeaders(Map.of("authorization", "Bearer x"), policy))
                .containsEntry("authorization", "***");
        assertThat(PayloadMasker.maskHeaders(Map.of("X-Plain", "v"), policy)).containsEntry("X-Plain", "v");
        assertThat(PayloadMasker.maskHeaders(null, policy)).isNull();
        assertThat(PayloadMasker.maskUrl("https://h/cb?t=abc&x=1&pan=Q#f", policy))
                .isEqualTo("https://h/cb?t=***&x=1&pan=***#f");
        assertThat(PayloadMasker.maskUrl("https://h/p", policy)).isEqualTo("https://h/p");
        assertThat(PayloadMasker.maskUrl(null, policy)).isNull();
        assertThat(PayloadMasker.maskForm("user=a&password=s3cret&flag", policy))
                .isEqualTo("user=a&password=***&flag");
    }

    @Test
    void bodiesByContentType() {
        assertThat(PayloadMasker.maskBody("{\"pan\":\"x\"}", "application/vnd.kyc+json; charset=utf-8", 11, policy))
                .isEqualTo("{\"pan\":\"***\"}");
        assertThat(PayloadMasker.maskBody("password=x", "application/x-www-form-urlencoded", 10, policy))
                .isEqualTo("password=***");
        assertThat(PayloadMasker.maskBody("<a pan='x'/>", "text/xml", 12, policy))
                .isEqualTo("<unparseable body: 12 bytes, text/xml>");
        assertThat(PayloadMasker.maskBody("{broken", "application/json", 7, policy))
                .isEqualTo("<unparseable body: 7 bytes, application/json>");
        assertThat(PayloadMasker.maskBody("raw", null, 3, policy)).isEqualTo("<unparseable body: 3 bytes, no content-type>");
        assertThat(PayloadMasker.maskBody(null, "application/json", 0, policy)).isNull();
        assertThat(PayloadMasker.maskBody("", "application/json", 0, policy)).isNull();
    }

    @Test
    void logMessagesAreMasked() {
        assertThat(MaskingMessageConverter.mask("login password=s3cret ok")).isEqualTo("login password=*** ok");
        assertThat(MaskingMessageConverter.mask("cb https://h/x?t=tok123&y=1")).isEqualTo("cb https://h/x?t=***&y=1");
        assertThat(MaskingMessageConverter.mask("auth Bearer abc.def")).isEqualTo("auth Bearer ***");
        assertThat(MaskingMessageConverter.mask("{TOKEN=a, pin=1234}")).isEqualTo("{TOKEN=***, pin=***}");
        assertThat(MaskingMessageConverter.mask("spin=3 opinion=fine")).isEqualTo("spin=3 opinion=fine");
        assertThat(MaskingMessageConverter.mask(null)).isNull();
    }
}
