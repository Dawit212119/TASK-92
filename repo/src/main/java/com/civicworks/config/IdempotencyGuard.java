package com.civicworks.config;

import com.civicworks.domain.entity.IdempotencyRecord;
import com.civicworks.exception.BusinessException;
import com.civicworks.service.IdempotencyService;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Thin helper that wraps idempotency check-then-execute logic so controllers
 * do not duplicate the pattern.
 *
 * Usage in a controller:
 * <pre>
 *   return idempotencyGuard.execute(
 *       idempotencyKey, actor.getId(), "PUBLISH_CONTENT",
 *       () -> ResponseEntity.ok(contentService.publishItem(id, actor)));
 * </pre>
 *
 * If idempotencyKey is null or blank the supplier is executed directly (no
 * idempotency tracking — callers should log a warning if the header is absent
 * for important mutations).
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotencyGuard(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes {@code action} exactly once per (userId, idempotencyKey) pair.
     * If the key has been seen before the original response is returned
     * immediately without re-executing the action.
     *
     * @param idempotencyKey  value of the Idempotency-Key header (may be null)
     * @param userId          authenticated user's id
     * @param actionType      label stored for auditing (e.g. "PUBLISH_CONTENT")
     * @param action          the actual work to perform
     * @param <T>             response body type
     */
    @SuppressWarnings("unchecked")
    public <T> ResponseEntity<T> execute(String idempotencyKey, UUID userId,
                                         String actionType, Supplier<ResponseEntity<T>> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(
                    "Idempotency-Key header is required for this operation.",
                    HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
        }

        // Check for an existing record with this key for this user.
        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            log.info("IDEMPOTENCY_REPLAY key={} actionType={} userId={}", idempotencyKey, actionType, userId);
            HttpStatus status = HttpStatus.valueOf(record.getResponseStatus() != null
                    ? record.getResponseStatus() : 200);
            try {
                T body = record.getResponseBody() != null
                        ? (T) objectMapper.readValue(record.getResponseBody(), Object.class)
                        : null;
                return ResponseEntity.status(status).body(body);
            } catch (JsonProcessingException e) {
                // Cached body unparseable — re-execute rather than returning garbage.
                log.warn("IDEMPOTENCY_REPLAY_PARSE_FAIL key={}, re-executing", idempotencyKey);
            }
        }

        // Execute and persist result.
        ResponseEntity<T> response = action.get();

        try {
            String body = objectMapper.writeValueAsString(response.getBody());
            idempotencyService.save(userId, idempotencyKey, actionType,
                    response.getStatusCode().value(), body);
        } catch (JsonProcessingException e) {
            // Non-fatal — we still return the real response; just can't cache it.
            log.warn("IDEMPOTENCY_STORE_FAIL key={} actionType={}: {}", idempotencyKey, actionType, e.getMessage());
        }

        return response;
    }
}
