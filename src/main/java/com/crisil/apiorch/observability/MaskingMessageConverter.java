package com.crisil.apiorch.observability;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * {@code %maskedMsg} for the {@code local}/{@code test} pattern layout (US-7.3 AC3, AC9): the formatted message with
 * the callback token, bearer tokens and {@code key=value} pairs of the always-mask keys replaced by {@code ***}. The
 * {@code prod} JSON layout applies the same regexes with logstash-logback-encoder's masking decorator. The key list
 * is set from {@code apiorch.logging.always-mask-keys} at startup ({@link #configure}).
 */
public class MaskingMessageConverter extends MessageConverter {

    private static volatile List<Pattern> patterns = patterns(List.of("password", "secret", "token", "otp", "pin",
            "clientSecret", "client_secret", "accessToken", "access_token", "refreshToken", "refresh_token"));

    public static void configure(List<String> keys) {
        patterns = patterns(keys);
    }

    static List<Pattern> patterns(List<String> keys) {
        String names = keys.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return List.of(
                Pattern.compile("(?<=[?&]t=)[^&\\s]+"),
                Pattern.compile("(?<=Bearer )\\S+"),
                Pattern.compile("(?i)(?<=\\b(?:" + names + ")=)[^\\s,}&\"]+"));
    }

    public static String mask(String message) {
        if (message == null) {
            return null;
        }
        String out = message;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(out);
            out = m.replaceAll(PayloadMasker.MASK);
        }
        return out;
    }

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }
}
