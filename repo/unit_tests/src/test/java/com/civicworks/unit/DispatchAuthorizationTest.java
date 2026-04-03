package com.civicworks.unit;

import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.entity.ZoneQueue;
import com.civicworks.domain.enums.Role;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests authorization enforcement on dispatch auxiliary endpoints:
 * - Driver eligibility: DRIVER self-only, DISPATCHER org-scoped, SYSTEM_ADMIN global.
 * - Zone queue: DISPATCHER org-scoped, SYSTEM_ADMIN global.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchAuthorizationTest {

    @Mock DispatchOrderRepository dispatchOrderRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock ZoneQueueRepository zoneQueueRepository;
    @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
    @Mock DriverCooldownRepository driverCooldownRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;

    private DispatchService dispatchService;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(
                dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                driverOnlineSessionRepository, driverCooldownRepository,
                userRepository, auditService, notificationService);
    }

    // ── eligibility: DRIVER self-only ────────────────────────────────────

    @Test
    void driver_canQueryOwnEligibility() {
        UUID driverId = UUID.randomUUID();
        User driver = userInOrg(driverId, Role.DRIVER, orgA);
        stubEligibleDriver(driverId);

        assertThatCode(() -> dispatchService.checkDriverEligibility(driver, driverId))
                .doesNotThrowAnyException();
    }

    @Test
    void driver_cannotQueryOtherDriverEligibility() {
        UUID myId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        User driver = userInOrg(myId, Role.DRIVER, orgA);

        assertThatThrownBy(() -> dispatchService.checkDriverEligibility(driver, otherId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getCode()).isEqualTo("DRIVER_SELF_ONLY");
                    assertThat(bex.getStatus().value()).isEqualTo(403);
                });
    }

    // ── eligibility: DISPATCHER org-scoped ───────────────────────────────

    @Test
    void dispatcher_canQueryDriverInSameOrg() {
        UUID driverId = UUID.randomUUID();
        User dispatcher = userInOrg(UUID.randomUUID(), Role.DISPATCHER, orgA);
        User driverEntity = userInOrg(driverId, Role.DRIVER, orgA);
        driverEntity.setRating(5.0);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driverEntity));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(driverId), any(LocalDate.class)))
                .thenReturn(30.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(eq(driverId), any(OffsetDateTime.class)))
                .thenReturn(false);

        assertThatCode(() -> dispatchService.checkDriverEligibility(dispatcher, driverId))
                .doesNotThrowAnyException();
    }

    @Test
    void dispatcher_cannotQueryDriverInDifferentOrg() {
        UUID driverId = UUID.randomUUID();
        User dispatcher = userInOrg(UUID.randomUUID(), Role.DISPATCHER, orgA);
        User driverEntity = userInOrg(driverId, Role.DRIVER, orgB);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driverEntity));

        assertThatThrownBy(() -> dispatchService.checkDriverEligibility(dispatcher, driverId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getCode()).isEqualTo("DISPATCH_CROSS_TENANT_FORBIDDEN");
                    assertThat(bex.getStatus().value()).isEqualTo(403);
                });
    }

    // ── eligibility: SYSTEM_ADMIN global ─────────────────────────────────

    @Test
    void systemAdmin_canQueryAnyDriverEligibility() {
        UUID driverId = UUID.randomUUID();
        User admin = userInOrg(UUID.randomUUID(), Role.SYSTEM_ADMIN, orgA);
        stubEligibleDriver(driverId);

        assertThatCode(() -> dispatchService.checkDriverEligibility(admin, driverId))
                .doesNotThrowAnyException();
    }

    // ── queue: DISPATCHER org-scoped ─────────────────────────────────────

    @Test
    void dispatcher_seesOnlyOwnOrgQueueEntries() {
        User dispatcher = userInOrg(UUID.randomUUID(), Role.DISPATCHER, orgA);
        ZoneQueue qA = queueEntry(orgA);
        when(zoneQueueRepository.findByZoneAndStatusAndOrg(zoneId, "WAITING", orgA))
                .thenReturn(List.of(qA));

        List<ZoneQueue> result = dispatchService.getQueueForZone(zoneId, dispatcher);

        assertThat(result).hasSize(1).containsExactly(qA);
        verify(zoneQueueRepository).findByZoneAndStatusAndOrg(zoneId, "WAITING", orgA);
        verify(zoneQueueRepository, never()).findByZoneIdAndStatusOrderByQueuePosition(any(), any());
    }

    @Test
    void dispatcherOrgA_cannotSeeOrgBQueueEntries() {
        User dispatcher = userInOrg(UUID.randomUUID(), Role.DISPATCHER, orgA);
        when(zoneQueueRepository.findByZoneAndStatusAndOrg(zoneId, "WAITING", orgA))
                .thenReturn(List.of());

        List<ZoneQueue> result = dispatchService.getQueueForZone(zoneId, dispatcher);

        assertThat(result).isEmpty();
    }

    // ── queue: SYSTEM_ADMIN global ───────────────────────────────────────

    @Test
    void systemAdmin_seesAllQueueEntries() {
        User admin = userInOrg(UUID.randomUUID(), Role.SYSTEM_ADMIN, orgA);
        ZoneQueue qA = queueEntry(orgA);
        ZoneQueue qB = queueEntry(orgB);
        when(zoneQueueRepository.findByZoneIdAndStatusOrderByQueuePosition(zoneId, "WAITING"))
                .thenReturn(List.of(qA, qB));

        List<ZoneQueue> result = dispatchService.getQueueForZone(zoneId, admin);

        assertThat(result).hasSize(2);
        verify(zoneQueueRepository).findByZoneIdAndStatusOrderByQueuePosition(zoneId, "WAITING");
        verify(zoneQueueRepository, never()).findByZoneAndStatusAndOrg(any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private User userInOrg(UUID userId, Role role, UUID orgId) {
        User u = new User();
        u.setId(userId);
        u.setRole(role);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    private void stubEligibleDriver(UUID driverId) {
        User driver = new User();
        driver.setId(driverId);
        driver.setRating(5.0);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(driverId), any(LocalDate.class)))
                .thenReturn(30.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(eq(driverId), any(OffsetDateTime.class)))
                .thenReturn(false);
    }

    private ZoneQueue queueEntry(UUID orgId) {
        ZoneQueue q = new ZoneQueue();
        q.setId(UUID.randomUUID());
        q.setZoneId(zoneId);
        q.setOrderId(UUID.randomUUID());
        q.setQueuePosition(1);
        q.setStatus("WAITING");
        return q;
    }
}
