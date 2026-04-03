package com.civicworks.unit;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.ResolveDiscrepancyRequest;
import com.civicworks.dto.ShiftHandoverReportDTO;
import com.civicworks.dto.ShiftHandoverRequest;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.repository.*;
import com.civicworks.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests covering every acceptance-blocker fix:
 *  A. Discrepancy tenant isolation
 *  B. Auditor read-only (AUDITOR cannot resolve)
 *  C. Org-scoped search with full filters/sort
 *  D. Typeahead suggestions ordered by recency
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AcceptanceBlockerTest {

    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();

    // ═══════════════════════════════════════════════════════════════════════
    //  A. Discrepancy tenant isolation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class DiscrepancyTenantIsolation {

        @Mock BillRepository billRepository;
        @Mock SettlementRepository settlementRepository;
        @Mock PaymentRepository paymentRepository;
        @Mock RefundRepository refundRepository;
        @Mock ShiftHandoverRepository shiftHandoverRepository;
        @Mock ShiftHandoverTotalRepository shiftHandoverTotalRepository;
        @Mock DiscrepancyCaseRepository discrepancyCaseRepository;
        @Mock UserRepository userRepository;
        @Mock AuditService auditService;
        @Mock NotificationService notificationService;

        PaymentService paymentService;
        final UUID caseId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            paymentService = new PaymentService(
                    billRepository, settlementRepository, paymentRepository,
                    refundRepository, shiftHandoverRepository, shiftHandoverTotalRepository,
                    discrepancyCaseRepository, userRepository, auditService, notificationService);
        }

        @Test
        void clerkOrgA_cannotResolve_discrepancyInOrgB() {
            when(discrepancyCaseRepository.findByIdAndOrganizationId(caseId, ORG_A))
                    .thenReturn(Optional.empty());

            ResolveDiscrepancyRequest req = new ResolveDiscrepancyRequest();
            req.setResolution(DiscrepancyStatus.APPROVED);

            assertThatThrownBy(() -> paymentService.resolveDiscrepancy(caseId, req, clerkIn(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void clerkOrgA_canResolve_discrepancyInOrgA() {
            DiscrepancyCase dc = discrepancyInOrg(ORG_A);
            when(discrepancyCaseRepository.findByIdAndOrganizationId(caseId, ORG_A))
                    .thenReturn(Optional.of(dc));
            when(discrepancyCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResolveDiscrepancyRequest req = new ResolveDiscrepancyRequest();
            req.setResolution(DiscrepancyStatus.APPROVED);

            assertThatCode(() -> paymentService.resolveDiscrepancy(caseId, req, clerkIn(ORG_A)))
                    .doesNotThrowAnyException();
        }

        @Test
        void systemAdmin_canResolve_anyDiscrepancy() {
            DiscrepancyCase dc = discrepancyInOrg(ORG_B);
            when(discrepancyCaseRepository.findById(caseId)).thenReturn(Optional.of(dc));
            when(discrepancyCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ResolveDiscrepancyRequest req = new ResolveDiscrepancyRequest();
            req.setResolution(DiscrepancyStatus.APPROVED);

            assertThatCode(() -> paymentService.resolveDiscrepancy(caseId, req, adminIn(ORG_A)))
                    .doesNotThrowAnyException();
        }

        @Test
        void findDiscrepancies_clerkOrgA_usesOrgScopedQuery() {
            when(discrepancyCaseRepository.findWithFiltersAndOrg(eq(ORG_A), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            paymentService.findDiscrepanciesPage(null, null, null,
                    org.springframework.data.domain.PageRequest.of(0, 20), clerkIn(ORG_A));

            verify(discrepancyCaseRepository).findWithFiltersAndOrg(eq(ORG_A), isNull(), isNull(), isNull(), any(Pageable.class));
            verify(discrepancyCaseRepository, never()).findWithFilters(any(), any(), any(), any(Pageable.class));
        }

        @Test
        void findDiscrepancies_admin_usesGlobalQuery() {
            when(discrepancyCaseRepository.findWithFilters(isNull(), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            paymentService.findDiscrepanciesPage(null, null, null,
                    org.springframework.data.domain.PageRequest.of(0, 20), adminIn(ORG_A));

            verify(discrepancyCaseRepository).findWithFilters(isNull(), isNull(), isNull(), any(Pageable.class));
        }

        @Test
        void processHandover_setsOrganizationId_onHandoverAndDiscrepancy() {
            when(shiftHandoverRepository.save(any())).thenAnswer(inv -> {
                ShiftHandover h = inv.getArgument(0);
                setField(h, "id", UUID.randomUUID());
                return h;
            });
            when(shiftHandoverTotalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.sumAmountByMethodForShift("S1"))
                    .thenReturn(List.<Object[]>of(new Object[]{PaymentMethod.CASH, 1000L}));
            when(userRepository.findByRole(Role.BILLING_CLERK)).thenReturn(List.of());
            when(discrepancyCaseRepository.save(any())).thenAnswer(inv -> {
                DiscrepancyCase c = inv.getArgument(0);
                setField(c, "id", UUID.randomUUID());
                return c;
            });

            ShiftHandoverRequest req = new ShiftHandoverRequest();
            ShiftHandoverRequest.MethodTotal mt = new ShiftHandoverRequest.MethodTotal();
            mt.setPaymentMethod(PaymentMethod.CASH);
            mt.setTotalAmountCents(5000L); // delta = 4000 > 100 → discrepancy
            req.setSubmittedTotals(List.of(mt));

            User actor = clerkIn(ORG_A);
            ShiftHandoverReportDTO report = paymentService.processHandover("S1", req, actor);

            assertThat(report.isDiscrepancyFlag()).isTrue();

            ArgumentCaptor<ShiftHandover> hCap = ArgumentCaptor.forClass(ShiftHandover.class);
            verify(shiftHandoverRepository).save(hCap.capture());
            assertThat(hCap.getValue().getOrganizationId()).isEqualTo(ORG_A);

            ArgumentCaptor<DiscrepancyCase> dCap = ArgumentCaptor.forClass(DiscrepancyCase.class);
            verify(discrepancyCaseRepository).save(dCap.capture());
            assertThat(dCap.getValue().getOrganizationId()).isEqualTo(ORG_A);
        }

        private DiscrepancyCase discrepancyInOrg(UUID orgId) {
            DiscrepancyCase dc = new DiscrepancyCase();
            dc.setId(caseId);
            dc.setOrganizationId(orgId);
            dc.setStatus(DiscrepancyStatus.OPEN);
            dc.setDeltaCents(500L);
            return dc;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  B. Auditor read-only enforcement
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class AuditorReadOnly {

        @Test
        void resolveEndpoint_excludesAuditor_fromPreAuthorize() {
            // The @PreAuthorize on resolveDiscrepancy must NOT include AUDITOR.
            // This is a static verification of the annotation.
            // The actual enforcement is Spring Security; here we verify the
            // service-layer org check would work IF the request got through,
            // but importantly the @PreAuthorize blocks it at controller level.
            //
            // We verify by confirming the annotation text in the controller
            // was changed from "hasRole('AUDITOR')" to "hasRole('BILLING_CLERK')"
            // via a reflective check on the controller method.
            try {
                var method = com.civicworks.controller.BillingController.class
                        .getMethod("resolveDiscrepancy", UUID.class,
                                com.civicworks.dto.ResolveDiscrepancyRequest.class,
                                org.springframework.security.core.Authentication.class);
                var annotation = method.getAnnotation(
                        org.springframework.security.access.prepost.PreAuthorize.class);
                assertThat(annotation).isNotNull();
                String value = annotation.value();
                assertThat(value).doesNotContain("AUDITOR");
                assertThat(value).contains("BILLING_CLERK");
                assertThat(value).contains("SYSTEM_ADMIN");
            } catch (NoSuchMethodException e) {
                fail("resolveDiscrepancy method not found on BillingController");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  C. Org-scoped search uses full filters/sort
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class OrgScopedSearchFilters {

        @Mock ContentItemRepository contentItemRepository;
        @Mock ContentService contentService;
        @Mock SearchHistoryRepository searchHistoryRepository;

        SearchService searchService;

        @BeforeEach
        void setUp() {
            searchService = new SearchService(contentService, contentItemRepository, searchHistoryRepository);
        }

        @Test
        void orgScopedUser_getsFullFilterSort_notReducedSearch() {
            when(contentItemRepository.fullTextSearchWithFiltersByOrg(
                    eq(ORG_A), eq("test"), isNull(), eq("NEWS"),
                    eq("downtown"), eq(100L), eq(5000L), eq("title"), eq("ASC")))
                    .thenReturn(List.of());

            User editor = editorIn(ORG_A);
            searchService.search("test", null, "NEWS", "downtown", 100L, 5000L, "title", "ASC", editor);

            // Must call the full-filter org-scoped method
            verify(contentItemRepository).fullTextSearchWithFiltersByOrg(
                    eq(ORG_A), eq("test"), isNull(), eq("NEWS"),
                    eq("downtown"), eq(100L), eq(5000L), eq("title"), eq("ASC"));
            // Must NOT fall back to the basic org-scoped search
            verify(contentItemRepository, never()).fullTextSearchByOrg(any(), any(), any(), any());
        }

        @Test
        void adminUser_getsGlobalFullFilterSort() {
            when(contentItemRepository.fullTextSearchWithFilters(
                    eq("test"), isNull(), eq("NEWS"), eq("downtown"),
                    eq(100L), eq(5000L), eq("title"), eq("ASC")))
                    .thenReturn(List.of());

            User admin = adminIn(ORG_A);
            searchService.search("test", null, "NEWS", "downtown", 100L, 5000L, "title", "ASC", admin);

            verify(contentItemRepository).fullTextSearchWithFilters(
                    eq("test"), isNull(), eq("NEWS"), eq("downtown"),
                    eq(100L), eq(5000L), eq("title"), eq("ASC"));
        }

        @Test
        void sortBy_invalidValue_defaultsToDate() {
            when(contentItemRepository.fullTextSearchWithFilters(
                    any(), any(), any(), any(), any(), any(), eq("date"), eq("DESC")))
                    .thenReturn(List.of());

            searchService.search("test", null, null, null, null, null, "INVALID", null, adminIn(ORG_A));

            verify(contentItemRepository).fullTextSearchWithFilters(
                    any(), any(), any(), any(), any(), any(), eq("date"), eq("DESC"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  D. Typeahead suggestion recency ordering
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class TypeaheadRecency {

        @Mock ContentItemRepository contentItemRepository;
        @Mock ContentService contentService;
        @Mock SearchHistoryRepository searchHistoryRepository;

        SearchService searchService;

        @BeforeEach
        void setUp() {
            searchService = new SearchService(contentService, contentItemRepository, searchHistoryRepository);
        }

        @Test
        void suggestions_delegateToRecencyOrderedQuery() {
            UUID userId = UUID.randomUUID();
            when(contentItemRepository.findRecentQuerySuggestions(userId, "tes"))
                    .thenReturn(List.of("test3_newest", "test1_older"));

            List<String> result = searchService.getSuggestions("tes", userId);

            assertThat(result).containsExactly("test3_newest", "test1_older");
            verify(contentItemRepository).findRecentQuerySuggestions(userId, "tes");
        }

        @Test
        void suggestions_emptyPrefix_returnsEmpty() {
            List<String> result = searchService.getSuggestions("", UUID.randomUUID());
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ═══════════════════════════════════════════════════════════════════════

    static User clerkIn(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.BILLING_CLERK);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User adminIn(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.SYSTEM_ADMIN);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User editorIn(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.CONTENT_EDITOR);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
