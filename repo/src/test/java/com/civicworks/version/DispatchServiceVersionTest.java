package com.civicworks.version;

import com.civicworks.domain.entity.DispatchOrder;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.OrderStatus;
import com.civicworks.domain.enums.RejectionReason;
import com.civicworks.dto.RejectOrderRequest;
import com.civicworks.exception.VersionConflictException;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving optimistic-locking enforcement in DispatchService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchServiceVersionTest {

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

        // Driver eligibility stubs (let it pass eligibility check)
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(driverId), any(LocalDate.class)))
                .thenReturn(60.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(any(), any()))
                .thenReturn(false);
    }

    // -----------------------------------------------------------------------
    // acceptOrder
    // -----------------------------------------------------------------------

    @Test
    void acceptOrder_correctVersion_succeeds() {
        DispatchOrder order = pendingOrder(3);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> dispatchService.acceptOrder(orderId, 3, driver))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptOrder_staleVersion_throws409() {
        DispatchOrder order = pendingOrder(3);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 1, driver))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> {
                    VersionConflictException vce = (VersionConflictException) ex;
                    assertThat(vce.getEntityType()).isEqualTo("DispatchOrder");
                    assertThat(vce.getServerVersion()).isEqualTo(3);
                    assertThat(vce.getStateSummary()).containsEntry("status", "PENDING");
                });
    }

    @Test
    void acceptOrder_nullVersion_throws409() {
        DispatchOrder order = pendingOrder(3);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, null, driver))
                .isInstanceOf(VersionConflictException.class);
    }

    // -----------------------------------------------------------------------
    // rejectOrder
    // -----------------------------------------------------------------------

    @Test
    void rejectOrder_correctVersion_succeeds() {
        DispatchOrder order = pendingOrder(2);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(driverCooldownRepository.save(any())).thenAnswer(inv -> {
            com.civicworks.domain.entity.DriverCooldown dc = inv.getArgument(0);
            dc.setId(UUID.randomUUID());
            return dc;
        });

        RejectOrderRequest req = rejectRequest(2, RejectionReason.TOO_FAR);
        assertThatCode(() -> dispatchService.rejectOrder(orderId, req, driver))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectOrder_staleVersion_throws409() {
        DispatchOrder order = pendingOrder(2);
        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        RejectOrderRequest req = rejectRequest(0, RejectionReason.TOO_FAR); // stale
        assertThatThrownBy(() -> dispatchService.rejectOrder(orderId, req, driver))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> assertThat(((VersionConflictException) ex).getServerVersion()).isEqualTo(2));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private DispatchOrder pendingOrder(int version) {
        DispatchOrder o = new DispatchOrder();
        o.setId(orderId);
        o.setStatus(OrderStatus.PENDING);
        setVersion(o, version);
        return o;
    }

    private RejectOrderRequest rejectRequest(int entityVersion, RejectionReason reason) {
        RejectOrderRequest r = new RejectOrderRequest();
        r.setRejectionReason(reason);
        r.setEntityVersion(entityVersion);
        return r;
    }

    private static void setVersion(Object entity, int version) {
        try {
            var field = entity.getClass().getDeclaredField("version");
            field.setAccessible(true);
            field.set(entity, version);
        } catch (Exception e) {
            throw new RuntimeException("Could not set version on " + entity.getClass().getSimpleName(), e);
        }
    }
}
