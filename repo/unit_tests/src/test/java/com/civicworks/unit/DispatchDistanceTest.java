package com.civicworks.unit;

import com.civicworks.domain.entity.DispatchOrder;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.OrderStatus;
import com.civicworks.dto.AcceptOrderRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.repository.*;
import com.civicworks.service.AuditService;
import com.civicworks.service.DispatchService;
import com.civicworks.service.NotificationService;
import com.civicworks.util.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the 3-mile distance acceptance constraint introduced in
 * DispatchService.acceptOrder(orderId, version, driver, AcceptOrderRequest).
 *
 * Coordinate pairs are chosen so the Haversine distance is unambiguously
 * inside or outside the 3-mile limit:
 *
 *   Base pickup:  lat=0.0, lng=0.0
 *   Close driver: lat=0.01, lng=0.0  ≈ 0.69 miles  → within limit
 *   Far driver:   lat=0.10, lng=0.0  ≈ 6.90 miles  → exceeds limit
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchDistanceTest {

    // Fixed pickup location (used as order.pickupLat / pickupLng)
    private static final double PICKUP_LAT = 0.0;
    private static final double PICKUP_LNG = 0.0;

    // ~0.69 miles from pickup — well within the 3-mile limit
    private static final double CLOSE_DRIVER_LAT = 0.01;
    private static final double CLOSE_DRIVER_LNG = 0.0;

    // ~6.9 miles from pickup — clearly beyond the 3-mile limit
    private static final double FAR_DRIVER_LAT = 0.10;
    private static final double FAR_DRIVER_LNG = 0.0;

    @Mock DispatchOrderRepository dispatchOrderRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock ZoneQueueRepository zoneQueueRepository;
    @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
    @Mock DriverCooldownRepository driverCooldownRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;

    private DispatchService dispatchService;

    private final UUID orderId  = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private User driver;

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(
                dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                driverOnlineSessionRepository, driverCooldownRepository,
                userRepository, auditService, notificationService);

        driver = new User();
        driver.setId(driverId);
        driver.setRating(4.8); // above MIN_DRIVER_RATING
        driver.setRole(com.civicworks.domain.enums.Role.SYSTEM_ADMIN);

        // Stub eligibility prerequisites so the driver passes all non-distance checks
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(
                eq(driverId), any(LocalDate.class))).thenReturn(60.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(
                any(UUID.class), any(OffsetDateTime.class))).thenReturn(false);
    }

    // ── GeoUtils sanity checks ────────────────────────────────────────────────

    @Test
    void haversine_closeCoords_isWithinThreeMiles() {
        double dist = GeoUtils.haversineDistanceMiles(PICKUP_LAT, PICKUP_LNG,
                CLOSE_DRIVER_LAT, CLOSE_DRIVER_LNG);
        assertThat(dist).isLessThan(3.0);
    }

    @Test
    void haversine_farCoords_exceedsThreeMiles() {
        double dist = GeoUtils.haversineDistanceMiles(PICKUP_LAT, PICKUP_LNG,
                FAR_DRIVER_LAT, FAR_DRIVER_LNG);
        assertThat(dist).isGreaterThan(3.0);
    }

    @Test
    void haversine_samePoint_isZero() {
        double dist = GeoUtils.haversineDistanceMiles(40.7128, -74.0060, 40.7128, -74.0060);
        assertThat(dist).isEqualTo(0.0);
    }

    // ── checkDriverEligibility distance overload ──────────────────────────────

    @Test
    void eligibility_withinRange_remainsEligible() {
        Map<String, Object> result = dispatchService.checkDriverEligibility(
                driverId, CLOSE_DRIVER_LAT, CLOSE_DRIVER_LNG, PICKUP_LAT, PICKUP_LNG);

        assertThat(result.get("eligible")).isEqualTo(true);
        assertThat((String) result.get("reasons")).doesNotContain("miles");
    }

    @Test
    void eligibility_beyondRange_markedIneligible() {
        Map<String, Object> result = dispatchService.checkDriverEligibility(
                driverId, FAR_DRIVER_LAT, FAR_DRIVER_LNG, PICKUP_LAT, PICKUP_LNG);

        assertThat(result.get("eligible")).isEqualTo(false);
        assertThat((String) result.get("reasons")).contains("miles");
        assertThat((String) result.get("reasons")).contains("3.0");
    }

    @Test
    void eligibility_nullDriverCoords_distanceCheckSkipped() {
        // When driver coords are absent the base eligibility result is returned unchanged
        Map<String, Object> result = dispatchService.checkDriverEligibility(
                driverId, null, null, PICKUP_LAT, PICKUP_LNG);

        assertThat(result.get("eligible")).isEqualTo(true);
    }

    @Test
    void eligibility_nullOrderCoords_distanceCheckSkipped() {
        Map<String, Object> result = dispatchService.checkDriverEligibility(
                driverId, CLOSE_DRIVER_LAT, CLOSE_DRIVER_LNG, null, null);

        assertThat(result.get("eligible")).isEqualTo(true);
    }

    // ── acceptOrder with distance enforcement ─────────────────────────────────

    @Test
    void acceptOrder_driverTooFar_throwsBusinessException() {
        DispatchOrder order = pendingOrderWithPickup(PICKUP_LAT, PICKUP_LNG);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        AcceptOrderRequest request = acceptRequest(FAR_DRIVER_LAT, FAR_DRIVER_LNG);

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("miles");
    }

    @Test
    void acceptOrder_driverWithinRange_succeeds() {
        DispatchOrder order = pendingOrderWithPickup(PICKUP_LAT, PICKUP_LNG);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcceptOrderRequest request = acceptRequest(CLOSE_DRIVER_LAT, CLOSE_DRIVER_LNG);

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptOrder_noDriverCoords_throwsBusinessException() {
        // Null request body — driver coordinates are mandatory; must be rejected with 400
        DispatchOrder order = pendingOrderWithPickup(PICKUP_LAT, PICKUP_LNG);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("coordinates");
    }

    @Test
    void acceptOrder_noPickupCoords_throwsBusinessException() {
        // Order has no pickup coords — acceptance must now be rejected (cannot verify distance)
        DispatchOrder order = pendingOrderWithPickup(null, null);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        AcceptOrderRequest request = acceptRequest(FAR_DRIVER_LAT, FAR_DRIVER_LNG);

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pickup coordinates");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DispatchOrder pendingOrderWithPickup(Double lat, Double lng) {
        DispatchOrder o = new DispatchOrder();
        o.setId(orderId);
        o.setStatus(OrderStatus.PENDING);
        if (lat != null) o.setPickupLat(BigDecimal.valueOf(lat));
        if (lng != null) o.setPickupLng(BigDecimal.valueOf(lng));
        // version field defaults to 0 via @Version; no need to set it here
        return o;
    }

    private AcceptOrderRequest acceptRequest(double driverLat, double driverLng) {
        AcceptOrderRequest r = new AcceptOrderRequest();
        r.setDriverLat(driverLat);
        r.setDriverLng(driverLng);
        return r;
    }
}
