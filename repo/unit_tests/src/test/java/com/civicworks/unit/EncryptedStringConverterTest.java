package com.civicworks.unit;

import com.civicworks.config.EncryptedStringConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EncryptedStringConverter.
 *
 * The AES-256-GCM key is supplied via the system property
 * {@code resident.id.encryption.key}, which is set globally for all Maven
 * Surefire test runs in pom.xml — no per-test setup required.
 */
class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @Test
    void encryptDecrypt_roundTrip_returnsOriginalValue() {
        String plaintext = "RES-00123456";

        String encrypted = converter.convertToDatabaseColumn(plaintext);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptedValue_differFromPlaintext() {
        String plaintext = "RES-00123456";

        String encrypted = converter.convertToDatabaseColumn(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
    }

    @Test
    void nullInput_encryptReturnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void nullInput_decryptReturnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void eachEncryption_producesDifferentCiphertext() {
        // GCM uses a random IV per call, so two encryptions of the same value differ
        String plaintext = "RES-99999999";

        String enc1 = converter.convertToDatabaseColumn(plaintext);
        String enc2 = converter.convertToDatabaseColumn(plaintext);

        assertThat(enc1).isNotEqualTo(enc2);
        // But both decrypt to the same plaintext
        assertThat(converter.convertToEntityAttribute(enc1)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(enc2)).isEqualTo(plaintext);
    }
}
