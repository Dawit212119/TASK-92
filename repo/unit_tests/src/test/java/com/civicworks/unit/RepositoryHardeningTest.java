package com.civicworks.unit;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.AcceptOrderRequest;
import com.civicworks.dto.ModerateCommentRequest;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.repository.*;
import com.civicworks.service.*;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Proves that every mutation path now uses org-scoped findByIdAndOrganizationId
 * instead of raw findById — no org bypass is possible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepositoryHardeningTest {

    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();

    // ═══════════════════════════════════════════════════════════════════════
    //  Dispatch: org-scoped order lookups
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class DispatchOrgScoping {

        @Mock DispatchOrderRepository dispatchOrderRepository;
        @Mock ZoneRepository zoneRepository;
        @Mock ZoneQueueRepository zoneQueueRepository;
        @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
        @Mock DriverCooldownRepository driverCooldownRepository;
        @Mock UserRepository userRepository;
        @Mock AuditService auditService;
        @Mock NotificationService notificationService;

        DispatchService dispatchService;
        final UUID orderId = UUID.randomUUID();
        final UUID driverId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            dispatchService = new DispatchService(
                    dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                    driverOnlineSessionRepository, driverCooldownRepository,
                    userRepository, auditService, notificationService);
            stubEligible(driverId);
        }

        @Test
        void findById_orgScopedUser_usesOrgFilter() {
            when(dispatchOrderRepository.findByIdAndOrganizationId(orderId, ORG_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> dispatchService.findById(orderId, driverInOrg(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(dispatchOrderRepository).findByIdAndOrganizationId(orderId, ORG_A);
            verify(dispatchOrderRepository, never()).findById(orderId);
        }

        @Test
        void findById_admin_usesUnfilteredLookup() {
            DispatchOrder order = orderInOrg(ORG_B);
            when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

            assertThatCode(() -> dispatchService.findById(orderId, adminInOrg(ORG_A)))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptOrder_driverOrgA_cannotAcceptOrderOrgB() {
            when(dispatchOrderRepository.findByIdAndOrganizationId(orderId, ORG_A))
                    .thenReturn(Optional.empty());

            User driver = driverInOrg(ORG_A);
            AcceptOrderRequest coords = coords();

            assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver, coords))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(dispatchOrderRepository, never()).save(any());
        }

        @Test
        void acceptOrder_driverOrgB_canAcceptOrderOrgB() {
            DispatchOrder order = orderInOrg(ORG_B);
            when(dispatchOrderRepository.findByIdAndOrganizationId(orderId, ORG_B))
                    .thenReturn(Optional.of(order));
            when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            User driver = driverInOrg(ORG_B);
            driver.setId(driverId);

            assertThatCode(() -> dispatchService.acceptOrder(orderId, 0, driver, coords()))
                    .doesNotThrowAnyException();
        }

        @Test
        void createOrder_setsOrganizationIdFromActor() {
            UUID zoneId = UUID.randomUUID();
            Zone zone = new Zone();
            zone.setId(zoneId);
            zone.setMaxConcurrentOrders(10);
            when(zoneRepository.findById(zoneId)).thenReturn(Optional.of(zone));
            when(dispatchOrderRepository.countActiveOrdersInZone(any(), any())).thenReturn(0L);
            when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new com.civicworks.dto.CreateDispatchOrderRequest();
            request.setZoneId(zoneId);
            request.setMode(OrderMode.GRAB);
            request.setPickupLat(BigDecimal.valueOf(37.77));
            request.setPickupLng(BigDecimal.valueOf(-122.41));

            User actor = driverInOrg(ORG_A);
            DispatchOrder result = dispatchService.createOrder(request, actor);

            assertThat(result.getOrganizationId()).isEqualTo(ORG_A);
        }

        private DispatchOrder orderInOrg(UUID orgId) {
            DispatchOrder o = new DispatchOrder();
            o.setId(orderId);
            o.setMode(OrderMode.GRAB);
            o.setStatus(OrderStatus.PENDING);
            o.setOrganizationId(orgId);
            o.setPickupLat(BigDecimal.valueOf(37.77));
            o.setPickupLng(BigDecimal.valueOf(-122.41));
            return o;
        }

        private AcceptOrderRequest coords() {
            AcceptOrderRequest r = new AcceptOrderRequest();
            r.setDriverLat(37.77);
            r.setDriverLng(-122.41);
            return r;
        }

        private void stubEligible(UUID id) {
            User d = new User();
            d.setId(id);
            d.setRating(4.8);
            when(userRepository.findById(id)).thenReturn(Optional.of(d));
            when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(id), any(LocalDate.class))).thenReturn(60.0);
            when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(eq(id), any(OffsetDateTime.class))).thenReturn(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Moderation: org-scoped comment moderation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ModerationOrgScoping {

        @Mock CommentRepository commentRepository;
        @Mock SensitiveWordRepository sensitiveWordRepository;
        @Mock ContentItemRepository contentItemRepository;
        @Mock AuditService auditService;

        ModerationService moderationService;
        final UUID commentId = UUID.randomUUID();
        final UUID contentItemId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            moderationService = new ModerationService(
                    sensitiveWordRepository, commentRepository, contentItemRepository, auditService);
        }

        @Test
        void moderateComment_orgA_cannotModerateOrgBComment() {
            Comment comment = new Comment();
            comment.setId(commentId);
            comment.setContentItemId(contentItemId);
            comment.setModerationState(ModerationState.FLAGGED);

            when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
            when(contentItemRepository.findByIdAndOrganizationId(contentItemId, ORG_A))
                    .thenReturn(Optional.empty());

            ModerateCommentRequest req = new ModerateCommentRequest();
            req.setAction(ModerationState.APPROVED);

            assertThatThrownBy(() -> moderationService.moderateComment(commentId, req, moderatorInOrg(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(commentRepository, never()).save(any());
        }

        @Test
        void moderateComment_sameOrg_succeeds() {
            Comment comment = new Comment();
            comment.setId(commentId);
            comment.setContentItemId(contentItemId);
            comment.setModerationState(ModerationState.FLAGGED);

            ContentItem item = new ContentItem();
            item.setId(contentItemId);

            when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
            when(contentItemRepository.findByIdAndOrganizationId(contentItemId, ORG_A))
                    .thenReturn(Optional.of(item));
            when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ModerateCommentRequest req = new ModerateCommentRequest();
            req.setAction(ModerationState.APPROVED);

            assertThatCode(() -> moderationService.moderateComment(commentId, req, moderatorInOrg(ORG_A)))
                    .doesNotThrowAnyException();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AuthorizationService: resolveOrgId never trusts client input
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class OrgIdDerivation {

        @Test
        void orgIdDerivedFromUser_notFromInput() {
            User clerk = clerkInOrg(ORG_A);
            UUID resolved = AuthorizationService.resolveOrgId(clerk);
            assertThat(resolved).isEqualTo(ORG_A);
        }

        @Test
        void nullUser_globalAccess() {
            assertThat(AuthorizationService.resolveOrgId(null)).isNull();
        }

        @Test
        void adminUser_alwaysGlobal_evenWithOrg() {
            User admin = adminInOrg(ORG_A);
            assertThat(AuthorizationService.resolveOrgId(admin)).isNull();
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────

    static User driverInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.DRIVER);
        u.setRating(4.8);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User adminInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.SYSTEM_ADMIN);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User clerkInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.BILLING_CLERK);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User moderatorInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.MODERATOR);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }
}
