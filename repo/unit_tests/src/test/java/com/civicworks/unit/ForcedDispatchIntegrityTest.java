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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies forced-dispatch (DISPATCHER_ASSIGNED) acceptance integrity:
 * - Only the designated driver may accept
 * - Another driver gets FORBIDDEN
 * - Reassignment enforces 30-minute window
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForcedDispatchIntegrityTest {

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
    private final UUID designatedDriverId = UUID.randomUUID();
    private final UUID otherDriverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(
                dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                driverOnlineSessionRepository, driverCooldownRepository,
                userRepository, auditService, notificationService);

        // Stub eligibility for designated driver
        User designatedDriver = new User();
        designatedDriver.setId(designatedDriverId);
        designatedDriver.setRating(4.8);
        when(userRepository.findById(designatedDriverId)).thenReturn(Optional.of(designatedDriver));

        User otherDriver = new User();
        otherDriver.setId(otherDriverId);
        otherDriver.setRating(4.8);
        when(userRepository.findById(otherDriverId)).thenReturn(Optional.of(otherDriver));

        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(any(), any(LocalDate.class))).thenReturn(60.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(any(), any(OffsetDateTime.class))).thenReturn(false);
    }

    @Test
    void designatedDriver_canAcceptForcedOrder() {
        DispatchOrder order = forcedOrder(designatedDriverId);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User driver = driverUser(designatedDriverId);
        AcceptOrderRequest request = driverRequest();

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .doesNotThrowAnyException();
    }

    @Test
    void otherDriver_cannotAcceptForcedOrder_getsForbidden() {
        DispatchOrder order = forcedOrder(designatedDriverId);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        User driver = driverUser(otherDriverId);
        AcceptOrderRequest request = driverRequest();

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("designated driver");
    }

    @Test
    void grabOrder_anyDriverCanAccept() {
        DispatchOrder order = grabOrder();
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User driver = driverUser(otherDriverId);
        AcceptOrderRequest request = driverRequest();

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, driver, request))
                .doesNotThrowAnyException();
    }

    @Test
    void reassign_beforeThirtyMinutes_throwsBusinessException() {
        DispatchOrder order = forcedOrder(designatedDriverId);
        order.setAssignedAt(OffsetDateTime.now().minusMinutes(10)); // only 10 min ago
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        User dispatcher = driverUser(UUID.randomUUID());

        assertThatThrownBy(() -> dispatchService.reassignOrder(orderId, 0, otherDriverId, dispatcher))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("30 minutes");
    }

    @Test
    void reassign_afterThirtyMinutes_succeeds() {
        DispatchOrder order = forcedOrder(designatedDriverId);
        order.setAssignedAt(OffsetDateTime.now().minusMinutes(35)); // 35 min ago
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User dispatcher = driverUser(UUID.randomUUID());

        assertThatCode(() -> dispatchService.reassignOrder(orderId, 0, otherDriverId, dispatcher))
                .doesNotThrowAnyException();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private DispatchOrder forcedOrder(UUID assignedTo) {
        DispatchOrder o = new DispatchOrder();
        o.setId(orderId);
        o.setMode(OrderMode.DISPATCHER_ASSIGNED);
        o.setStatus(OrderStatus.ASSIGNED);
        o.setAssignedDriverId(assignedTo);
        o.setPickupLat(BigDecimal.valueOf(37.77));
        o.setPickupLng(BigDecimal.valueOf(-122.41));
        o.setForcedFlag(true);
        return o;
    }

    private DispatchOrder grabOrder() {
        DispatchOrder o = new DispatchOrder();
        o.setId(orderId);
        o.setMode(OrderMode.GRAB);
        o.setStatus(OrderStatus.PENDING);
        o.setPickupLat(BigDecimal.valueOf(37.77));
        o.setPickupLng(BigDecimal.valueOf(-122.41));
        return o;
    }

    private User driverUser(UUID id) {
        User u = new User();
        u.setId(id);
        u.setRating(4.8);
        return u;
    }

    private AcceptOrderRequest driverRequest() {
        AcceptOrderRequest r = new AcceptOrderRequest();
        r.setDriverLat(37.77);
        r.setDriverLng(-122.41);
        return r;
    }
}
