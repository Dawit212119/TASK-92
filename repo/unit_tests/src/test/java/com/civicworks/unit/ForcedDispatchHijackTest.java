package com.civicworks.unit;

import com.civicworks.domain.entity.DispatchOrder;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.OrderMode;
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
import static org.mockito.Mockito.*;

/**
 * Exhaustive tests for the forced-dispatch hijack prevention rule:
 *
 * The guard fires when ANY of these is true:
 *   - order.forcedFlag == true
 *   - order.mode == DISPATCHER_ASSIGNED
 *   - order.status == ASSIGNED
 *
 * AND order.assignedDriverId is non-null and does not match the accepting driver.
 *
 * Additional scenarios:
 *   - reassignment before 30 minutes MUST fail
 *   - reassignment after 30 minutes succeeds
 *   - assignedDriverId is never overwritten by a non-designated driver
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForcedDispatchHijackTest {

    @Mock DispatchOrderRepository dispatchOrderRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock ZoneQueueRepository zoneQueueRepository;
    @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
    @Mock DriverCooldownRepository driverCooldownRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;

    private DispatchService dispatchService;

    private final UUID orderId = UUID.randomUUID();
    private final UUID driverA = UUID.randomUUID();
    private final UUID driverB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(
                dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                driverOnlineSessionRepository, driverCooldownRepository,
                userRepository, auditService, notificationService);

        stubEligible(driverA);
        stubEligible(driverB);
    }

    // ── forcedFlag == true path ──────────────────────────────────────────

    @Test
    void driverB_cannotAccept_orderForcedToDriverA_viaForcedFlag() {
        DispatchOrder order = order(OrderMode.GRAB, OrderStatus.PENDING, driverA);
        order.setForcedFlag(true);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, user(driverB), coords()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(dispatchOrderRepository, never()).save(any());
    }

    // ── status == ASSIGNED path ─────────────────────────────────────────

    @Test
    void driverB_cannotAccept_assignedOrderForDriverA_viaStatusCheck() {
        // GRAB mode but status is ASSIGNED with assignedDriverId set
        DispatchOrder order = order(OrderMode.GRAB, OrderStatus.ASSIGNED, driverA);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, user(driverB), coords()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(dispatchOrderRepository, never()).save(any());
    }

    // ── mode == DISPATCHER_ASSIGNED path ────────────────────────────────

    @Test
    void driverB_cannotAccept_dispatcherAssignedOrderForDriverA() {
        DispatchOrder order = order(OrderMode.DISPATCHER_ASSIGNED, OrderStatus.ASSIGNED, driverA);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, user(driverB), coords()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("designated driver");
    }

    // ── designated driver can always accept ──────────────────────────────

    @Test
    void driverA_canAccept_orderAssignedToDriverA() {
        DispatchOrder order = order(OrderMode.DISPATCHER_ASSIGNED, OrderStatus.ASSIGNED, driverA);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, user(driverA), coords()))
                .doesNotThrowAnyException();
    }

    // ── no assignedDriverId → anyone can accept ─────────────────────────

    @Test
    void anyDriver_canAccept_pendingOrderWithNoAssignment() {
        DispatchOrder order = order(OrderMode.GRAB, OrderStatus.PENDING, null);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, user(driverB), coords()))
                .doesNotThrowAnyException();
    }

    // ── reassignment 30-min rule ────────────────────────────────────────

    @Test
    void reassign_before30min_fails() {
        DispatchOrder order = order(OrderMode.DISPATCHER_ASSIGNED, OrderStatus.ASSIGNED, driverA);
        order.setAssignedAt(OffsetDateTime.now().minusMinutes(15));
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.reassignOrder(orderId, 0, driverB, user(driverA)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("30 minutes");
    }

    @Test
    void reassign_after30min_succeeds() {
        DispatchOrder order = order(OrderMode.DISPATCHER_ASSIGNED, OrderStatus.ASSIGNED, driverA);
        order.setAssignedAt(OffsetDateTime.now().minusMinutes(31));
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> dispatchService.reassignOrder(orderId, 0, driverB, user(driverA)))
                .doesNotThrowAnyException();
    }

    // ── assignedDriverId not overwritten on rejection ────────────────────

    @Test
    void rejection_clearsAssignment() {
        DispatchOrder order = order(OrderMode.DISPATCHER_ASSIGNED, OrderStatus.ASSIGNED, driverA);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(driverCooldownRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new com.civicworks.dto.RejectOrderRequest();
        req.setRejectionReason(com.civicworks.domain.enums.RejectionReason.TOO_FAR);
        req.setEntityVersion(0);

        DispatchOrder result = dispatchService.rejectOrder(orderId, req, user(driverA));

        assertThat(result.getAssignedDriverId()).isNull();
        assertThat(result.getAssignedAt()).isNull();
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private DispatchOrder order(OrderMode mode, OrderStatus status, UUID assignedTo) {
        DispatchOrder o = new DispatchOrder();
        o.setId(orderId);
        o.setMode(mode);
        o.setStatus(status);
        o.setAssignedDriverId(assignedTo);
        o.setPickupLat(BigDecimal.valueOf(37.77));
        o.setPickupLng(BigDecimal.valueOf(-122.41));
        return o;
    }

    private User user(UUID id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private AcceptOrderRequest coords() {
        AcceptOrderRequest r = new AcceptOrderRequest();
        r.setDriverLat(37.77);
        r.setDriverLng(-122.41);
        return r;
    }

    private void stubEligible(UUID driverId) {
        User d = new User();
        d.setId(driverId);
        d.setRating(4.8);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(d));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(driverId), any(LocalDate.class))).thenReturn(60.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(eq(driverId), any(OffsetDateTime.class))).thenReturn(false);
    }
}
