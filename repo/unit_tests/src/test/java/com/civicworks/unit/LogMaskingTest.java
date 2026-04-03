package com.civicworks.unit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.civicworks.config.SensitiveDataMaskingConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SensitiveDataMaskingConverter}.
 *
 * The converter is tested in isolation: an {@link ILoggingEvent} is mocked so
 * that {@code getFormattedMessage()} returns a controlled string, then
 * {@code convert()} is called and the output is asserted to contain "***"
 * in place of the sensitive value.
 */
class LogMaskingTest {

    private SensitiveDataMaskingConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SensitiveDataMaskingConverter();
    }

    // ── residentId= masking ───────────────────────────────────────────────────

    @Test
    void residentId_valueIsMasked() {
        String output = convert("Processing account residentId=RES-00123456 for billing");

        assertThat(output).contains("residentId=***");
        assertThat(output).doesNotContain("RES-00123456");
    }

    @Test
    void residentId_uuidValueIsMasked() {
        String output = convert("AUDIT residentId=550e8400-e29b-41d4-a716-446655440000 action=LOGIN");

        assertThat(output).contains("residentId=***");
        assertThat(output).doesNotContain("550e8400-e29b-41d4-a716-446655440000");
    }

    // ── resident_id= masking ──────────────────────────────────────────────────

    @Test
    void resident_id_valueIsMasked() {
        String output = convert("DB query resident_id=RES-99999999 returned 1 row");

        assertThat(output).contains("resident_id=***");
        assertThat(output).doesNotContain("RES-99999999");
    }

    // ── multiple occurrences ──────────────────────────────────────────────────

    @Test
    void multipleOccurrences_allMasked() {
        String output = convert("residentId=AAA and residentId=BBB in same message");

        assertThat(output).doesNotContain("AAA");
        assertThat(output).doesNotContain("BBB");
        // Both replaced
        assertThat(output).isEqualTo("residentId=*** and residentId=*** in same message");
    }

    @Test
    void bothKeyForms_allMasked() {
        String output = convert("residentId=AAA resident_id=BBB");

        assertThat(output).contains("residentId=***");
        assertThat(output).contains("resident_id=***");
        assertThat(output).doesNotContain("AAA");
        assertThat(output).doesNotContain("BBB");
    }

    // ── non-sensitive content is preserved ───────────────────────────────────

    @Test
    void messageWithoutSensitiveData_isUnchanged() {
        String message = "Bill 123 settled for accountId=abc-456";
        String output  = convert(message);

        assertThat(output).isEqualTo(message);
    }

    @Test
    void emptyMessage_isUnchanged() {
        assertThat(convert("")).isEqualTo("");
    }

    @Test
    void ordinaryText_preserved() {
        String message = "ORDER_ACCEPTED driverId=abc-123 orderId=xyz-789";
        assertThat(convert(message)).isEqualTo(message);
    }

    // ── key preserved after masking ───────────────────────────────────────────

    @Test
    void keyIsPreservedAfterMasking() {
        String output = convert("residentId=SOME-VALUE");

        // Key must still be in output so log lines remain parseable
        assertThat(output).startsWith("residentId=");
        assertThat(output).isEqualTo("residentId=***");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private String convert(String message) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        // MessageConverter.convert() calls event.getFormattedMessage() internally
        // via super.convert(), so we stub it here.
        when(event.getMessage()).thenReturn(message);
        when(event.getArgumentArray()).thenReturn(null);
        return converter.convert(event);
    }
}
