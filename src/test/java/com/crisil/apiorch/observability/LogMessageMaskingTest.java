package com.crisil.apiorch.observability;

import static net.logstash.logback.argument.StructuredArguments.keyValue;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;

/**
 * US-7.3 AC9: with the production JSON encoder's masking, built from the {@code <path>} and {@code <value>} entries
 * of {@code logback-spring.xml} itself, a sensitive value rendered into a log message is masked both as a field
 * and inside the message text. The pattern layout's converter is covered in {@link PayloadMaskerTest}.
 */
class LogMessageMaskingTest {

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static LogstashEncoder prodEncoder(LoggerContext context) throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
        MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
        decorator.setDefaultMask("***");
        Matcher paths = Pattern.compile("<path>([^<]+)</path>").matcher(xml);
        int pathCount = 0;
        while (paths.find()) {
            decorator.addPath(paths.group(1));
            pathCount++;
        }
        Matcher values = Pattern.compile("<value>([^<]+)</value>").matcher(xml);
        int valueCount = 0;
        while (values.find()) {
            decorator.addValue(unescape(values.group(1)));
            valueCount++;
        }
        assertThat(pathCount).isGreaterThanOrEqualTo(5);
        assertThat(valueCount).isEqualTo(3);
        decorator.start();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setJsonGeneratorDecorator(decorator);
        encoder.start();
        return encoder;
    }

    @Test
    void fieldAndMessageTextAreMasked() throws Exception {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        LogstashEncoder encoder = prodEncoder(context);
        LoggingEvent event = new LoggingEvent("x", context.getLogger("test"), Level.INFO, "login {}", null,
                new Object[] {keyValue("password", "s3cret")});
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(line).doesNotContain("s3cret");
        assertThat(line).contains("\"password\":\"***\"").contains("login password=***");

        LoggingEvent callback = new LoggingEvent("x", context.getLogger("test"), Level.INFO,
                "callback https://apiorch/cb?t=tok-1&a=1 with Bearer abc.def", null, null);
        String cb = new String(encoder.encode(callback), StandardCharsets.UTF_8);
        assertThat(cb).doesNotContain("tok-1").doesNotContain("abc.def").contains("t=***");
    }
}
