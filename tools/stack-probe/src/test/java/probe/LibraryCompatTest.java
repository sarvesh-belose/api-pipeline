package probe;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.dashjoin.jsonata.Jsonata;
import net.logstash.logback.argument.StructuredArguments;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Library-level gate items that need no Spring context. */
class LibraryCompatTest {

    record Step(String id, String endpoint) {
    }

    @Test
    void jackson3StrictBindingCanonicalJsonAndDeepCopy() {
        System.out.println("GATE-INFO jackson3 default FAIL_ON_UNKNOWN_PROPERTIES="
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
        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("branch")).isEqualTo("001");
    }

    @Test
    void logstashEncoder9MasksFieldsOnJackson3() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(ctx);
        MaskingJsonGeneratorDecorator mask = new MaskingJsonGeneratorDecorator();
        mask.setDefaultMask("***");
        mask.addPath("password");
        // Finding: a structured argument rendered through a {} placeholder also lands in the free-text
        // "message" field, which a path mask does not reach. A value regex covers it.
        mask.addValue("(?<=password=)\\S+");
        mask.start(); // 9.0: the decorator is a logback LifeCycle; XML config starts it, programmatic use must
        encoder.addDecorator(mask);
        encoder.start();

        LoggingEvent event = new LoggingEvent(getClass().getName(), ctx.getLogger("probe"), Level.INFO, "login {}", null,
                new Object[] {StructuredArguments.keyValue("password", "s3cret")});
        String json = new String(encoder.encode(event), UTF_8);
        assertThat(json).contains("\"password\":\"***\"").doesNotContain("s3cret");
    }
}
