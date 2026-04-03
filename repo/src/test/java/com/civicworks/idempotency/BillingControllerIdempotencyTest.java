package com.civicworks.idempotency;

import com.civicworks.config.AuthUtils;
import com.civicworks.config.IdempotencyGuard;
import com.civicworks.controller.BillingController;
import com.civicworks.domain.entity.BillingRun;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.BillingRunStatus;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.GlobalExceptionHandler;
import com.civicworks.service.BillingService;
import com.civicworks.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-slice test proving:
 *   1. Idempotency-Key header missing → 400 IDEMPOTENCY_KEY_REQUIRED
 *   2. First request with a key → action executed, 201 returned
 *   3. Repeat with same key → action NOT re-executed, original response replayed
 *
 * Uses a real IdempotencyGuard backed by a mock IdempotencyService so the
 * DB-persistence contract is verified at the boundary.
 */
@WebMvcTest(controllers = BillingController.class)
@Import({IdempotencyGuard.class, GlobalExceptionHandler.class})
class BillingControllerIdempotencyTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean BillingService billingService;
    @MockBean PaymentService paymentService;
    @MockBean AuthUtils authUtils;

    // We need a real IdempotencyGuard wired with a mock IdempotencyService.
    // @WebMvcTest only loads the controller slice; we @Import the guard.
    // The guard's IdempotencyService dependency must be a @MockBean so Spring
    // can inject it into the imported guard.
    @MockBean com.civicworks.service.IdempotencyService idempotencyService;

    private static final String CREATE_BILLING_RUN_URL = "/api/v1/billing/billing-runs";

    @BeforeEach
    void setUp() {
        User mockUser = new User();
        mockUser.setId(UUID.randomUUID());
        mockUser.setUsername("clerk");
        when(authUtils.resolveUser(any())).thenReturn(mockUser);
    }

    // -----------------------------------------------------------------------
    // Missing Idempotency-Key → 400
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "BILLING_CLERK")
    void createBillingRun_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post(CREATE_BILLING_RUN_URL)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(billingRunBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verify(billingService, never()).createBillingRun(any(), any());
    }

    // -----------------------------------------------------------------------
    // First call → action executed
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "BILLING_CLERK")
    void createBillingRun_withKey_firstCall_executesAndReturns201() throws Exception {
        String key = UUID.randomUUID().toString();
        BillingRun run = fakeBillingRun();

        when(idempotencyService.findExisting(any(), eq(key)))
                .thenReturn(java.util.Optional.empty());
        when(idempotencyService.save(any(), any(), any(), anyInt(), any()))
                .thenReturn(new com.civicworks.domain.entity.IdempotencyRecord());
        when(billingService.createBillingRun(any(), any())).thenReturn(run);

        mockMvc.perform(post(CREATE_BILLING_RUN_URL)
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(billingRunBody()))
                .andExpect(status().isCreated());

        verify(billingService, times(1)).createBillingRun(any(), any());
        verify(idempotencyService).save(any(), eq(key), eq("CREATE_BILLING_RUN"), eq(201), any());
    }

    // -----------------------------------------------------------------------
    // Repeat call → cached response, action NOT re-executed
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "BILLING_CLERK")
    void createBillingRun_withKey_secondCall_replaysCachedResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        BillingRun run = fakeBillingRun();

        // Simulate a previously-stored record
        com.civicworks.domain.entity.IdempotencyRecord cached =
                new com.civicworks.domain.entity.IdempotencyRecord();
        cached.setResponseStatus(201);
        cached.setResponseBody(objectMapper.writeValueAsString(run));

        when(idempotencyService.findExisting(any(), eq(key)))
                .thenReturn(java.util.Optional.of(cached));

        mockMvc.perform(post(CREATE_BILLING_RUN_URL)
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(billingRunBody()))
                .andExpect(status().isCreated());

        // Service must NOT be called on replay
        verify(billingService, never()).createBillingRun(any(), any());
        // Nothing new is saved to DB
        verify(idempotencyService, never()).save(any(), any(), any(), anyInt(), any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String billingRunBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "cycleDate", LocalDate.now().toString(),
                "billingCycle", "MONTHLY"
        ));
    }

    private BillingRun fakeBillingRun() {
        BillingRun run = new BillingRun();
        run.setId(UUID.randomUUID());
        run.setStatus(BillingRunStatus.PENDING);
        return run;
    }
}
