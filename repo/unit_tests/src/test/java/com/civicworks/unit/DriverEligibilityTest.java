package com.civicworks.unit;

import com.civicworks.domain.entity.User;
import com.civicworks.repository.*;
import com.civicworks.service.AuditService;
import com.civicworks.service.DispatchService;
import com.civicworks.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DispatchService.checkDriverEligibility():
 *
 * A driver is eligible only when ALL three conditions pass:
 *  1. rating >= 4.2
 *  2. online minutes today >= 15
 *  3. no active cooldown
 *
 * Tests each condition individually and in combination.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DriverEligibilityTest {

    @Mock DispatchOrderRepository dispatchOrderRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock ZoneQueueRepository zoneQueueRepository;
    @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
    @Mock DriverCooldownRepository driverCooldownRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;

    private DispatchService dispatchService;
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(
                dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                driverOnlineSessionRepository, driverCooldownRepository,
                userRepository, auditService, notificationService);
    }

    // ── all conditions pass → eligible ───────────────────────────────────────

    @Test
    void allConditionsPass_driverIsEligible() {
        stubDriver(5.0, 30.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(true);
        assertThat((String) result.get("reasons")).isEmpty();
    }

    @Test
    void ratingExactlyAtMinimum_isEligible() {
        stubDriver(4.2, 20.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(true);
    }

    @Test
    void onlineMinutesExactlyAtMinimum_isEligible() {
        stubDriver(4.5, 15.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(true);
    }

    // ── rating too low ────────────────────────────────────────────────────────

    @Test
    void ratingBelowMinimum_notEligible() {
        stubDriver(4.1, 30.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
        assertThat((String) result.get("reasons")).contains("Rating");
        assertThat((String) result.get("reasons")).contains("4.2");
    }

    @Test
    void ratingZero_notEligible() {
        stubDriver(0.0, 30.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
    }

    @Test
    void nullRating_treatedAsZero_notEligible() {
        User driver = driverWithRating(null);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(
                eq(driverId), any(LocalDate.class))).thenReturn(30.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(
                eq(driverId), any(OffsetDateTime.class))).thenReturn(false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
    }

    // ── online minutes too low ────────────────────────────────────────────────

    @Test
    void onlineMinutesBelowMinimum_notEligible() {
        stubDriver(5.0, 10.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
        assertThat((String) result.get("reasons")).contains("15");
    }

    @Test
    void noOnlineSessionsToday_notEligible() {
        User driver = driverWithRating(4.8);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(
                eq(driverId), any(LocalDate.class))).thenReturn(null); // no sessions
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(
                eq(driverId), any(OffsetDateTime.class))).thenReturn(false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
        assertThat((double) result.get("onlineMinutesToday")).isEqualTo(0.0);
    }

    // ── active cooldown ───────────────────────────────────────────────────────

    @Test
    void inCooldown_notEligible() {
        stubDriver(5.0, 30.0, true);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
        assertThat(result.get("inCooldown")).isEqualTo(true);
        assertThat((String) result.get("reasons")).contains("cooldown");
    }

    // ── multiple failures ─────────────────────────────────────────────────────

    @Test
    void ratingLowAndCooldown_bothReasonsReported() {
        stubDriver(3.0, 30.0, true);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
        String reasons = (String) result.get("reasons");
        assertThat(reasons).contains("Rating");
        assertThat(reasons).contains("cooldown");
    }

    @Test
    void allThreeConditionsFail_notEligible_allReasonsReported() {
        stubDriver(2.0, 5.0, true);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result.get("eligible")).isEqualTo(false);
        String reasons = (String) result.get("reasons");
        assertThat(reasons).contains("Rating");
        assertThat(reasons).contains("15");
        assertThat(reasons).contains("cooldown");
    }

    // ── response shape ────────────────────────────────────────────────────────

    @Test
    void responseContainsAllRequiredFields() {
        stubDriver(5.0, 20.0, false);

        Map<String, Object> result = dispatchService.checkDriverEligibility(driverId);

        assertThat(result).containsKeys("driverId", "eligible", "rating",
                "onlineMinutesToday", "inCooldown", "reasons");
        assertThat(result.get("driverId")).isEqualTo(driverId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubDriver(double rating, double onlineMinutes, boolean inCooldown) {
        User driver = driverWithRating(rating);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(
                eq(driverId), any(LocalDate.class))).thenReturn(onlineMinutes);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(
                eq(driverId), any(OffsetDateTime.class))).thenReturn(inCooldown);
    }

    private User driverWithRating(Double rating) {
        User u = new User();
        u.setId(driverId);
        u.setRating(rating);
        return u;
    }
}
