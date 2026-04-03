package com.civicworks.unit;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.AcceptOrderRequest;
import com.civicworks.dto.CreateSettlementRequest;
import com.civicworks.repository.*;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that event-driven notifications are created for key business events:
 *  - bill created (via executeBillingRun)
 *  - payment completed (via createSettlement)
 *  - dispatch assigned (via createOrder with forcedFlag)
 *  - dispatch accepted (via acceptOrder)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventNotificationTest {

    // ── Billing mocks ────────────────────────────────────────────────────

    @Mock FeeItemRepository feeItemRepository;
    @Mock BillingRunRepository billingRunRepository;
    @Mock BillRepository billRepository;
    @Mock BillDiscountRepository billDiscountRepository;
    @Mock LateFeeEventRepository lateFeeEventRepository;
    @Mock AccountRepository accountRepository;
    @Mock UsageRecordRepository usageRecordRepository;
    @Mock AuditService auditService;
    @Mock QuartzSchedulerConfig quartzSchedulerConfig;
    @Mock NotificationService notificationService;
    @Mock UserRepository userRepository;

    // ── Dispatch mocks ───────────────────────────────────────────────────

    @Mock DispatchOrderRepository dispatchOrderRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock ZoneQueueRepository zoneQueueRepository;
    @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
    @Mock DriverCooldownRepository driverCooldownRepository;

    // ── Payment mocks ────────────────────────────────────────────────────

    @Mock SettlementRepository settlementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock RefundRepository refundRepository;
    @Mock ShiftHandoverRepository shiftHandoverRepository;
    @Mock ShiftHandoverTotalRepository shiftHandoverTotalRepository;
    @Mock DiscrepancyCaseRepository discrepancyCaseRepository;

    private BillingService billingService;
    private DispatchService dispatchService;
    private PaymentService paymentService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingService = new BillingService(
                feeItemRepository, billingRunRepository, billRepository,
                billDiscountRepository, lateFeeEventRepository, accountRepository,
                usageRecordRepository, auditService, quartzSchedulerConfig,
                notificationService, userRepository);

        dispatchService = new DispatchService(
                dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                driverOnlineSessionRepository, driverCooldownRepository,
                userRepository, auditService, notificationService);

        paymentService = new PaymentService(
                billRepository, settlementRepository, paymentRepository,
                refundRepository, shiftHandoverRepository, shiftHandoverTotalRepository,
                discrepancyCaseRepository, userRepository, auditService, notificationService);

        // Common stubs
        when(billRepository.save(any())).thenAnswer(inv -> {
            Bill b = inv.getArgument(0);
            if (b.getId() == null) {
                try { Field f = Bill.class.getDeclaredField("id"); f.setAccessible(true); f.set(b, UUID.randomUUID()); } catch (Exception ignored) {}
            }
            return b;
        });
        when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Org user lookup for notifications
        User orgUser = new User();
        orgUser.setId(UUID.randomUUID());
        when(userRepository.findByOrganizationId(orgId)).thenReturn(List.of(orgUser));
    }

    // ── BILL_CREATED ─────────────────────────────────────────────────────

    @Test
    void executeBillingRun_createsNotification_forEachBill() {
        UUID runId = UUID.randomUUID();
        BillingRun run = new BillingRun();
        run.setId(runId);
        run.setStatus(BillingRunStatus.PENDING);
        run.setCycleDate(LocalDate.now());

        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setOrganizationId(orgId);

        FeeItem fee = new FeeItem();
        fee.setId(UUID.randomUUID());
        fee.setCalculationType(CalculationType.FLAT);
        fee.setRateCents(5000L);
        fee.setOrganizationId(orgId);

        when(billingRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(billingRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(billRepository.findByBillingRunId(runId)).thenReturn(List.of());
        when(feeItemRepository.findByOrganizationId(orgId)).thenReturn(List.of(fee));

        billingService.executeBillingRun(runId);

        verify(notificationService, atLeastOnce()).createNotification(
                any(UUID.class), eq("BILL_CREATED"), anyString(), anyString(), anyString());
    }

    // ── PAYMENT_COMPLETED ────────────────────────────────────────────────

    @Test
    void createSettlement_createsNotification_onPaymentCompleted() {
        UUID billId = UUID.randomUUID();
        Bill bill = new Bill();
        bill.setId(billId);
        bill.setStatus(BillStatus.OPEN);
        bill.setBalanceCents(10000L);
        bill.setOrganizationId(orgId);
        setVersion(bill, 0);

        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        CreateSettlementRequest req = new CreateSettlementRequest();
        req.setSettlementMode(SettlementMode.FULL);
        req.setEntityVersion(0);

        User actor = new User();
        actor.setId(UUID.randomUUID());

        paymentService.createSettlement(billId, req, actor);

        verify(notificationService, atLeastOnce()).createNotification(
                any(UUID.class), eq("PAYMENT_COMPLETED"), anyString(), anyString(), anyString());
    }

    // ── DISPATCH_ASSIGNED ────────────────────────────────────────────────

    @Test
    void createOrder_forcedDispatch_createsNotificationForAssignedDriver() {
        UUID zoneId = UUID.randomUUID();
        Zone zone = new Zone();
        zone.setId(zoneId);
        zone.setMaxConcurrentOrders(10);

        when(zoneRepository.findById(zoneId)).thenReturn(Optional.of(zone));
        when(dispatchOrderRepository.countActiveOrdersInZone(any(), any())).thenReturn(0L);

        var request = new com.civicworks.dto.CreateDispatchOrderRequest();
        request.setZoneId(zoneId);
        request.setMode(OrderMode.DISPATCHER_ASSIGNED);
        request.setPickupLat(BigDecimal.valueOf(37.77));
        request.setPickupLng(BigDecimal.valueOf(-122.41));
        request.setForcedFlag(true);
        request.setAssignedDriverId(driverId);

        User dispatcher = new User();
        dispatcher.setId(UUID.randomUUID());

        dispatchService.createOrder(request, dispatcher);

        verify(notificationService).createNotification(
                eq(driverId), eq("DISPATCH_ASSIGNED"), anyString(), anyString(), anyString());
    }

    // ── DISPATCH_ACCEPTED ────────────────────────────────────────────────

    @Test
    void acceptOrder_createsNotificationForDriver() {
        UUID orderId = UUID.randomUUID();
        DispatchOrder order = new DispatchOrder();
        order.setId(orderId);
        order.setMode(OrderMode.GRAB);
        order.setStatus(OrderStatus.PENDING);
        order.setPickupLat(BigDecimal.valueOf(37.77));
        order.setPickupLng(BigDecimal.valueOf(-122.41));

        when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        User driver = new User();
        driver.setId(driverId);
        driver.setRating(4.8);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(driverId), any(LocalDate.class))).thenReturn(60.0);
        when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(eq(driverId), any(OffsetDateTime.class))).thenReturn(false);

        AcceptOrderRequest coords = new AcceptOrderRequest();
        coords.setDriverLat(37.77);
        coords.setDriverLng(-122.41);

        dispatchService.acceptOrder(orderId, 0, driver, coords);

        verify(notificationService).createNotification(
                eq(driverId), eq("DISPATCH_ACCEPTED"), anyString(), anyString(), anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void setVersion(Object target, int version) {
        try {
            Field f = target.getClass().getDeclaredField("version");
            f.setAccessible(true);
            f.set(target, version);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
