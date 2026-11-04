package probe;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.logstash.logback.argument.StructuredArguments;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Library-level gate items on the Boot 3.5 line (Jackson 2, logstash 8.1). */
class LibraryCompatTest {

    record Step(String id, String endpoint) {
    }

    @Test
    void jackson2StrictBindingCanonicalJsonAndDeepCopy() throws Exception {
        System.out.println("G3|jackson2 default FAIL_ON_UNKNOWN_PROPERTIES="
                + JsonMapper.builder().build().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

        JsonMapper strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        assertThatThrownBy(() -> strict.readValue("{\"id\":\"api1\",\"endpoint\":\"x\",\"dependOn\":[]}", Step.class))
                .isInstanceOf(UnrecognizedPropertyException.class);

        JsonMapper canonical = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        JsonNode tree = canonical.readTree("{\"b\":1,\"a\":{\"d\":[3,1,2],\"c\":true}}");
        String out = canonical.writeValueAsString(canonical.treeToValue(tree, Object.class));
        assertThat(out).isEqualTo("{\"a\":{\"c\":true,\"d\":[3,1,2]},\"b\":1}");

        JsonNode copy = tree.deepCopy();
        ((ObjectNode) copy).put("b", "***");
        assertThat(tree.get("b").asInt()).isEqualTo(1);
    }

    @Test
    void jsonataPartOfFieldMapping() {
        Map<String, Object> ctx = Map.of("steps", Map.of("api1", Map.of("body",
                Map.of("account", Map.of("number", "ACC-0012345")))));
        Object result = Jsonata.jsonata("{ 'branch': $substring(steps.api1.body.account.number, 4, 3) }").evaluate(ctx);
        assertThat(((Map<?, ?>) result).get("branch")).isEqualTo("001");
    }

    @Test
    void logstashEncoder8MasksFieldsAndMessageText() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(ctx);
        MaskingJsonGeneratorDecorator mask = new MaskingJsonGeneratorDecorator();
        mask.setDefaultMask("***");
        mask.addPath("password");
        mask.addValue("(?<=password=)\\S+");
        mask.start(); // without start() the delegate is null and encode() throws NPE (8.1 as well as 9.0)
        encoder.setJsonGeneratorDecorator(mask); // 8.x API; 9.0 renamed it to addDecorator
        encoder.start();

        LoggingEvent event = new LoggingEvent(getClass().getName(), ctx.getLogger("probe"), Level.INFO, "login {}", null,
                new Object[] {StructuredArguments.keyValue("password", "s3cret")});
        String json = new String(encoder.encode(event), UTF_8);
        assertThat(json).contains("\"password\":\"***\"").doesNotContain("s3cret");
    }
}
