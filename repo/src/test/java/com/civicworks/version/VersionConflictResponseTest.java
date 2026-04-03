package com.civicworks.version;

import com.civicworks.exception.GlobalExceptionHandler;
import com.civicworks.exception.VersionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test verifying the 409 response payload shape produced by
 * GlobalExceptionHandler.handleVersionConflict().
 *
 * This test is deliberately decoupled from HTTP/Spring so it runs
 * without any application context or database.
 */
class VersionConflictResponseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleVersionConflict_returnsHttpConflict() {
        VersionConflictException ex = conflict("Bill", 7,
                Map.of("status", "OPEN", "balanceCents", 50000L));

        ResponseEntity<Map<String, Object>> response = handler.handleVersionConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void handleVersionConflict_errorCodeIsVERSION_CONFLICT() {
        VersionConflictException ex = conflict("Payment", 3, Map.of("amountCents", 10000L));

        ResponseEntity<Map<String, Object>> response = handler.handleVersionConflict(ex);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error_code")).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void handleVersionConflict_detailsContainServerVersionAndStateSummary() {
        UUID entityId = UUID.randomUUID();
        Map<String, Object> state = Map.of("status", "PENDING", "driverId", "none");
        VersionConflictException ex = new VersionConflictException("DispatchOrder", entityId, 5, state);

        ResponseEntity<Map<String, Object>> response = handler.handleVersionConflict(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.getBody().get("details");
        assertThat(details).isNotNull();
        assertThat(details.get("entityType")).isEqualTo("DispatchOrder");
        assertThat(details.get("entityId")).isEqualTo(entityId.toString());
        assertThat(details.get("serverVersion")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) details.get("stateSummary");
        assertThat(summary).containsKey("status");
    }

    @Test
    void handleVersionConflict_messageIsHelpful() {
        VersionConflictException ex = conflict("ContentItem", 2, Map.of());

        ResponseEntity<Map<String, Object>> response = handler.handleVersionConflict(ex);

        String message = (String) response.getBody().get("message");
        assertThat(message).containsIgnoringCase("version");
        assertThat(message).containsIgnoringCase("refresh");
    }

    @Test
    void handleVersionConflict_nullStateSummary_doesNotThrow() {
        VersionConflictException ex = new VersionConflictException("Bill", UUID.randomUUID(), 1, null);

        ResponseEntity<Map<String, Object>> response = handler.handleVersionConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.getBody().get("details");
        assertThat(details.get("stateSummary")).isNotNull(); // default empty map
    }

    private static VersionConflictException conflict(String type, int serverVersion,
                                                       Map<String, Object> state) {
        return new VersionConflictException(type, UUID.randomUUID(), serverVersion, state);
    }
}
