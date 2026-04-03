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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies that accepting an order whose pickup coordinates are missing is
 * rejected with a 400 Bad Request — the distance constraint cannot be
 * evaluated without a trusted server-side pickup location.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchPickupCoordTest {

    private static final double DRIVER_LAT = 37.77;
    private static final double DRIVER_LNG = -122.41;

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
        driver.setRating(4.8);
        driver.setRole(com.civicworks.domain.enums.Role.SYSTEM_ADMIN);

        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(
                eq(driverId), any(LocalDate.class))).thenReturn(60.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(
                any(UUID.class), any(OffsetDateTime.class))).thenReturn(false);
    }

    @Test
    void acceptOrder_orderMissingPickupLat_throwsBusinessException400() {
        DispatchOrder order = orderWithPickup(null, BigDecimal.valueOf(-122.41));
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        AcceptOrderRequest request = driverRequest();

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("pickup coordinates");
    }

    @Test
    void acceptOrder_orderMissingPickupLng_throwsBusinessException400() {
        DispatchOrder order = orderWithPickup(BigDecimal.valueOf(37.77), null);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        AcceptOrderRequest request = driverRequest();

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("pickup coordinates");
    }

    @Test
    void acceptOrder_orderMissingBothPickupCoords_throwsBusinessException400() {
        DispatchOrder order = orderWithPickup(null, null);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        AcceptOrderRequest request = driverRequest();

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void acceptOrder_orderHasPickupCoords_proceedsNormally() {
        DispatchOrder order = orderWithPickup(BigDecimal.valueOf(37.77), BigDecimal.valueOf(-122.41));
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcceptOrderRequest request = driverRequest();

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .doesNotThrowAnyException();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DispatchOrder orderWithPickup(BigDecimal lat, BigDecimal lng) {
        DispatchOrder o = new DispatchOrder();
        o.setId(orderId);
        o.setStatus(OrderStatus.PENDING);
        o.setPickupLat(lat);
        o.setPickupLng(lng);
        return o;
    }

    private AcceptOrderRequest driverRequest() {
        AcceptOrderRequest r = new AcceptOrderRequest();
        r.setDriverLat(DRIVER_LAT);
        r.setDriverLng(DRIVER_LNG);
        return r;
    }
}
