package com.civicworks.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA AttributeConverter that transparently encrypts/decrypts the
 * {@code residentId} column using AES-256-GCM.
 *
 * The secret key is loaded once from the environment variable
 * {@code RESIDENT_ID_ENCRYPTION_KEY}, which must be a Base64-encoded 32-byte
 * (256-bit) value. If the variable is absent the application will refuse to
 * start with an {@link IllegalStateException}.
 *
 * Wire format stored in the DB column:
 *   Base64( IV(12 bytes) || ciphertext+tag )
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH  = 12; // bytes
    private static final int GCM_TAG_LENGTH = 128; // bits

    private static final SecretKey SECRET_KEY;

    static {
        // Primary source: environment variable (production).
        // Fallback: JVM system property resident.id.encryption.key (test harness).
        String envValue = System.getenv("RESIDENT_ID_ENCRYPTION_KEY");
        if (envValue == null || envValue.isBlank()) {
            envValue = System.getProperty("resident.id.encryption.key");
        }
        if (envValue == null || envValue.isBlank()) {
            throw new IllegalStateException(
                    "RESIDENT_ID_ENCRYPTION_KEY environment variable is not set. " +
                    "Provide a Base64-encoded 32-byte AES key.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(envValue.trim());
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "RESIDENT_ID_ENCRYPTION_KEY must decode to exactly 32 bytes (256 bits), " +
                    "got " + keyBytes.length + " bytes.");
        }
        SECRET_KEY = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV to ciphertext so we can recover it on decryption
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt residentId", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);

            byte[] iv         = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0,             iv,         0, iv.length);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt residentId", e);
        }
    }
}
