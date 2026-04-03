package com.civicworks.config;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback message converter that masks sensitive resident identifiers before
 * they are written to any log appender.
 *
 * <p>Patterns masked (case-sensitive key, any non-whitespace value):
 * <ul>
 *   <li>{@code residentId=<value>} → {@code residentId=***}</li>
 *   <li>{@code resident_id=<value>} → {@code resident_id=***}</li>
 * </ul>
 *
 * <p>Registered in {@code logback-spring.xml} under the conversion word
 * {@code maskedMsg} and substituted for {@code %msg} in every appender pattern.
 */
public class SensitiveDataMaskingConverter extends MessageConverter {

    /**
     * Matches either key form followed by any run of non-whitespace characters.
     * Group 1 captures the key (including the {@code =} sign) so it can be
     * preserved in the replacement string.
     */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(residentId=|resident_id=)\\S+"
    );

    private static final String MASK = "***";

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (message == null) {
            return null;
        }
        return SENSITIVE_PATTERN.matcher(message).replaceAll("$1" + MASK);
    }
}
