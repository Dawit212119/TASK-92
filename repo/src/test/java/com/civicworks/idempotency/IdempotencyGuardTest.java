package com.civicworks.idempotency;

import com.civicworks.config.IdempotencyGuard;
import com.civicworks.domain.entity.IdempotencyRecord;
import com.civicworks.exception.BusinessException;
import com.civicworks.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IdempotencyGuard — no Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock
    private IdempotencyService idempotencyService;

    private IdempotencyGuard guard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(idempotencyService, objectMapper);
    }

    // -----------------------------------------------------------------------
    // Missing key → 400
    // -----------------------------------------------------------------------

    @Test
    void execute_nullKey_throwsBadRequest() {
        assertThatThrownBy(() -> guard.execute(null, userId, "TEST_ACTION", () -> ResponseEntity.ok("body")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
                });
    }

    @Test
    void execute_blankKey_throwsBadRequest() {
        assertThatThrownBy(() -> guard.execute("   ", userId, "TEST_ACTION", () -> ResponseEntity.ok("body")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // -----------------------------------------------------------------------
    // New key → action executed, result cached in DB
    // -----------------------------------------------------------------------

    @Test
    void execute_newKey_executesActionAndPersists() {
        String key = UUID.randomUUID().toString();
        when(idempotencyService.findExisting(userId, key)).thenReturn(Optional.empty());

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<String> result = guard.execute(key, userId, "CREATE_THING", () -> {
            callCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body("created");
        });

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo("created");
        assertThat(callCount.get()).isEqualTo(1);

        // Response must be persisted so restarts can replay it
        verify(idempotencyService).save(eq(userId), eq(key), eq("CREATE_THING"), eq(201), anyString());
    }

    // -----------------------------------------------------------------------
    // Duplicate key → action NOT re-executed, cached response returned
    // -----------------------------------------------------------------------

    @Test
    void execute_duplicateKey_returnsCachedResponseWithoutReExecuting() throws Exception {
        String key = UUID.randomUUID().toString();

        IdempotencyRecord cached = new IdempotencyRecord();
        cached.setResponseStatus(201);
        cached.setResponseBody(objectMapper.writeValueAsString("created"));

        when(idempotencyService.findExisting(userId, key)).thenReturn(Optional.of(cached));

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<Object> result = guard.execute(key, userId, "CREATE_THING", () -> {
            callCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body("created");
        });

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(callCount.get())
                .as("Action must NOT be re-executed on replay")
                .isEqualTo(0);

        // No new DB write on replay
        verify(idempotencyService, never()).save(any(), any(), any(), anyInt(), any());
    }

    @Test
    void execute_duplicateKey_preservesOriginalStatusCode() throws Exception {
        String key = UUID.randomUUID().toString();

        IdempotencyRecord cached = new IdempotencyRecord();
        cached.setResponseStatus(200);
        cached.setResponseBody(objectMapper.writeValueAsString(42));

        when(idempotencyService.findExisting(userId, key)).thenReturn(Optional.of(cached));

        ResponseEntity<Object> result = guard.execute(key, userId, "SOME_ACTION", () ->
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("should-not-reach"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -----------------------------------------------------------------------
    // DB-persistence guarantee: different users with same key are independent
    // -----------------------------------------------------------------------

    @Test
    void execute_sameKeyDifferentUsers_treatedAsDistinct() throws Exception {
        String key = "shared-key";
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        when(idempotencyService.findExisting(userId1, key)).thenReturn(Optional.empty());
        when(idempotencyService.findExisting(userId2, key)).thenReturn(Optional.empty());

        guard.execute(key, userId1, "ACTION", () -> ResponseEntity.ok("u1"));
        guard.execute(key, userId2, "ACTION", () -> ResponseEntity.ok("u2"));

        // Each user's record is saved separately
        ArgumentCaptor<UUID> userCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(idempotencyService, times(2)).save(userCaptor.capture(), eq(key), any(), anyInt(), any());

        assertThat(userCaptor.getAllValues()).containsExactlyInAnyOrder(userId1, userId2);
    }

    // -----------------------------------------------------------------------
    // Corrupt cache → re-execute rather than returning garbage
    // -----------------------------------------------------------------------

    @Test
    void execute_corruptCachedBody_reExecutesGracefully() {
        String key = UUID.randomUUID().toString();

        IdempotencyRecord cached = new IdempotencyRecord();
        cached.setResponseStatus(201);
        cached.setResponseBody("{this is not valid json{{{{");

        when(idempotencyService.findExisting(userId, key)).thenReturn(Optional.of(cached));
        when(idempotencyService.save(any(), any(), any(), anyInt(), any())).thenReturn(cached);

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<String> result = guard.execute(key, userId, "ACTION", () -> {
            callCount.incrementAndGet();
            return ResponseEntity.ok("fresh");
        });

        assertThat(callCount.get())
                .as("Should re-execute when cached body is unparseable")
                .isEqualTo(1);
        assertThat(result.getBody()).isEqualTo("fresh");
    }
}
