package com.civicworks.service;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.OrderMode;
import com.civicworks.domain.enums.OrderStatus;
import com.civicworks.domain.enums.RejectionReason;
import com.civicworks.dto.AcceptOrderRequest;
import com.civicworks.dto.CreateDispatchOrderRequest;
import com.civicworks.dto.RejectOrderRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.*;
import com.civicworks.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private static final double MAX_DRIVER_MILES = 3.0;
    private static final double MIN_DRIVER_RATING = 4.2;
    private static final double MIN_ONLINE_MINUTES = 15.0;
    private static final long COOLDOWN_MINUTES = 30L;

    private final DispatchOrderRepository dispatchOrderRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneQueueRepository zoneQueueRepository;
    private final DriverOnlineSessionRepository driverOnlineSessionRepository;
    private final DriverCooldownRepository driverCooldownRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public DispatchService(DispatchOrderRepository dispatchOrderRepository,
                           ZoneRepository zoneRepository,
                           ZoneQueueRepository zoneQueueRepository,
                           DriverOnlineSessionRepository driverOnlineSessionRepository,
                           DriverCooldownRepository driverCooldownRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           NotificationService notificationService) {
        this.dispatchOrderRepository = dispatchOrderRepository;
        this.zoneRepository = zoneRepository;
        this.zoneQueueRepository = zoneQueueRepository;
        this.driverOnlineSessionRepository = driverOnlineSessionRepository;
        this.driverCooldownRepository = driverCooldownRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public DispatchOrder createOrder(CreateDispatchOrderRequest request, User actor) {
        Zone zone = zoneRepository.findById(request.getZoneId())
                .orElseThrow(() -> new ResourceNotFoundException("Zone", request.getZoneId()));

        long activeOrders = dispatchOrderRepository.countActiveOrdersInZone(request.getZoneId(),
                List.of(OrderStatus.ASSIGNED, OrderStatus.ACCEPTED));

        DispatchOrder order = new DispatchOrder();
        order.setZoneId(request.getZoneId());
        order.setMode(request.getMode());
        order.setPickupLat(request.getPickupLat());
        order.setPickupLng(request.getPickupLng());
        order.setDropoffLat(request.getDropoffLat());
        order.setDropoffLng(request.getDropoffLng());
        order.setForcedFlag(request.isForcedFlag());
        if (actor.getOrganization() != null) {
            order.setOrganizationId(actor.getOrganization().getId());
        }

        if (activeOrders >= zone.getMaxConcurrentOrders()) {
            order.setStatus(OrderStatus.QUEUED);
            DispatchOrder saved = dispatchOrderRepository.save(order);

            int nextPos = zoneQueueRepository.maxQueuePositionForZone(request.getZoneId()) + 1;
            ZoneQueue q = new ZoneQueue();
            q.setZoneId(request.getZoneId());
            q.setOrderId(saved.getId());
            q.setQueuePosition(nextPos);
            q.setStatus("WAITING");
            zoneQueueRepository.save(q);

            return saved;
        }

        // Handle forced dispatch assignment
        if (request.isForcedFlag() && request.getAssignedDriverId() != null) {
            order.setAssignedDriverId(request.getAssignedDriverId());
            order.setAssignedAt(OffsetDateTime.now());
            order.setStatus(OrderStatus.ASSIGNED);
            auditService.log(actor.getId(), "DISPATCH_ORDER_FORCED_ASSIGNED",
                    "dispatch_orders/", Map.of("driverId", request.getAssignedDriverId().toString()));
        } else {
            order.setStatus(OrderStatus.PENDING);
        }

        DispatchOrder saved = dispatchOrderRepository.save(order);

        // Notify assigned driver of new dispatch assignment
        if (saved.getAssignedDriverId() != null) {
            notifyDriver(saved.getAssignedDriverId(), "DISPATCH_ASSIGNED",
                    "New dispatch assignment",
                    "You have been assigned dispatch order " + saved.getId() + ".",
                    "dispatch_orders/" + saved.getId());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public DispatchOrder findById(UUID orderId) {
        return dispatchOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("DispatchOrder", orderId));
    }

    @Transactional(readOnly = true)
    public DispatchOrder findById(UUID orderId, User actor) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        if (orgId != null) {
            return dispatchOrderRepository.findByIdAndOrganizationId(orderId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("DispatchOrder", orderId));
        }
        return findById(orderId);
    }

    @Transactional
    public DispatchOrder acceptOrder(UUID orderId, Integer entityVersion, User driver) {
        DispatchOrder order = loadOrderWithTenantCheck(orderId, driver);

        requireVersion(entityVersion, order.getVersion(), "DispatchOrder", orderId,
                Map.of("status", order.getStatus().name()));

        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.ASSIGNED) {
            throw new BusinessException("Order is not in an acceptable state: " + order.getStatus(), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        requireForcedDispatchDriverMatch(order, driver);

        // Check eligibility
        checkDriverEligibility(driver.getId());

        order.setAssignedDriverId(driver.getId());
        order.setAssignedAt(OffsetDateTime.now());
        order.setStatus(OrderStatus.ACCEPTED);
        DispatchOrder saved = dispatchOrderRepository.save(order);

        MDC.put("orderId", orderId.toString());
        log.info("ORDER_ACCEPTED driverId={}", driver.getId());
        MDC.remove("orderId");

        auditService.log(driver.getId(), "DISPATCH_ORDER_ACCEPTED",
                "dispatch_orders/" + orderId, Map.of("driverId", driver.getId().toString()));

        notifyDriver(driver.getId(), "DISPATCH_ACCEPTED",
                "Dispatch order accepted",
                "You have accepted dispatch order " + orderId + ".",
                "dispatch_orders/" + orderId);

        return saved;
    }

    /**
     * Accept an order.  Driver coordinates are <strong>mandatory</strong>: if
     * {@code request} is null or either coordinate is absent the acceptance is
     * rejected immediately with a 400 Bad Request.  The distance check cannot
     * be bypassed by omitting coordinates.
     *
     * <p>Distance check is performed only when the order also has pickup
     * coordinates stored.  If the order has no pickup coordinates the distance
     * constraint is evaluated without a location source and passes through,
     * but the driver still undergoes all other eligibility checks (rating,
     * online time, cooldown).
     */
    @Transactional
    public DispatchOrder acceptOrder(UUID orderId, Integer entityVersion, User driver,
                                     AcceptOrderRequest request) {
        // Driver coordinates are mandatory — no bypass allowed.
        if (request == null || request.getDriverLat() == null || request.getDriverLng() == null) {
            throw new BusinessException(
                    "Driver coordinates (driverLat, driverLng) are required to accept an order",
                    HttpStatus.BAD_REQUEST);
        }

        DispatchOrder order = loadOrderWithTenantCheck(orderId, driver);

        // Pickup coordinates are required on the order — distance cannot be verified without them.
        if (order.getPickupLat() == null || order.getPickupLng() == null) {
            throw new BusinessException(
                    "Order pickup coordinates are missing; acceptance is not permitted without a verifiable pickup location",
                    HttpStatus.BAD_REQUEST);
        }

        requireVersion(entityVersion, order.getVersion(), "DispatchOrder", orderId,
                Map.of("status", order.getStatus().name()));

        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.ASSIGNED) {
            throw new BusinessException("Order is not in an acceptable state: " + order.getStatus(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        requireForcedDispatchDriverMatch(order, driver);

        Double driverLat = request.getDriverLat();
        Double driverLng = request.getDriverLng();
        Double orderLat  = order.getPickupLat().doubleValue();
        Double orderLng  = order.getPickupLng().doubleValue();

        Map<String, Object> eligibility = checkDriverEligibility(driver.getId(),
                driverLat, driverLng, orderLat, orderLng);
        if (!(boolean) eligibility.get("eligible")) {
            throw new BusinessException(
                    "Driver is not eligible to accept this order: " + eligibility.get("reasons"),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        order.setAssignedDriverId(driver.getId());
        order.setAssignedAt(OffsetDateTime.now());
        order.setStatus(OrderStatus.ACCEPTED);
        DispatchOrder saved = dispatchOrderRepository.save(order);

        MDC.put("orderId", orderId.toString());
        log.info("ORDER_ACCEPTED driverId={}", driver.getId());
        MDC.remove("orderId");

        auditService.log(driver.getId(), "DISPATCH_ORDER_ACCEPTED",
                "dispatch_orders/" + orderId, Map.of("driverId", driver.getId().toString()));

        notifyDriver(driver.getId(), "DISPATCH_ACCEPTED",
                "Dispatch order accepted",
                "You have accepted dispatch order " + orderId + ".",
                "dispatch_orders/" + orderId);

        return saved;
    }

    @Transactional
    public DispatchOrder rejectOrder(UUID orderId, RejectOrderRequest request, User driver) {
        DispatchOrder order = loadOrderWithTenantCheck(orderId, driver);

        requireVersion(request.getEntityVersion(), order.getVersion(), "DispatchOrder", orderId,
                Map.of("status", order.getStatus().name()));

        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.ASSIGNED
                && order.getStatus() != OrderStatus.ACCEPTED) {
            throw new BusinessException("Order cannot be rejected in state: " + order.getStatus(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        boolean wasForced = order.isForcedFlag();
        RejectionReason reason = request.getRejectionReason();

        // Store standardised rejection reason and reset order for reassignment.
        // The order goes back to PENDING (not a terminal REJECTED state) so that
        // another eligible driver can accept it.  The rejecting driver enters a
        // 30-minute cooldown that the eligibility check will enforce.
        order.setRejectionReason(reason.name());
        order.setAssignedDriverId(null);
        order.setAssignedAt(null);
        order.setStatus(OrderStatus.PENDING);
        DispatchOrder saved = dispatchOrderRepository.save(order);

        // 30-minute exclusion for the rejecting driver
        DriverCooldown cooldown = new DriverCooldown();
        cooldown.setDriverId(driver.getId());
        cooldown.setOrderId(orderId);
        cooldown.setCooldownUntil(OffsetDateTime.now().plusMinutes(COOLDOWN_MINUTES));
        driverCooldownRepository.save(cooldown);

        MDC.put("orderId", orderId.toString());
        log.info("ORDER_REJECTED driverId={} reason={} wasForced={}", driver.getId(), reason, wasForced);
        MDC.remove("orderId");

        auditService.log(driver.getId(), "DISPATCH_ORDER_REJECTED",
                "dispatch_orders/" + orderId,
                Map.of("reason", reason.name(), "forced", wasForced));

        return saved;
    }

    /**
     * Promotes the next WAITING entry in the zone queue to an active PENDING
     * order.  Call this whenever an order slot in the zone is freed (e.g. order
     * completed or cancelled externally).
     */
    @Transactional
    public void promoteNextQueuedOrder(UUID zoneId) {
        List<ZoneQueue> waiting =
                zoneQueueRepository.findByZoneIdAndStatusOrderByQueuePosition(zoneId, "WAITING");
        if (waiting.isEmpty()) return;

        ZoneQueue next = waiting.get(0);
        dispatchOrderRepository.findById(next.getOrderId()).ifPresent(queued -> {
            if (queued.getStatus() == OrderStatus.QUEUED) {
                queued.setStatus(OrderStatus.PENDING);
                dispatchOrderRepository.save(queued);
                log.info("QUEUE_ORDER_PROMOTED orderId={} zoneId={}", queued.getId(), zoneId);
            }
        });
        next.setStatus("PROMOTED");
        zoneQueueRepository.save(next);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> checkDriverEligibility(UUID driverId) {
        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", driverId));

        boolean eligible = true;
        StringBuilder reasons = new StringBuilder();

        // Check rating
        double rating = driver.getRating() != null ? driver.getRating() : 0.0;
        if (rating < MIN_DRIVER_RATING) {
            eligible = false;
            reasons.append("Rating ").append(rating).append(" < ").append(MIN_DRIVER_RATING).append(". ");
        }

        // Check online time today
        Double minutesToday = driverOnlineSessionRepository.sumMinutesForDriverOnDate(driverId, LocalDate.now());
        double mins = minutesToday != null ? minutesToday : 0.0;
        if (mins < MIN_ONLINE_MINUTES) {
            eligible = false;
            reasons.append("Online today: ").append(mins).append(" min < ").append(MIN_ONLINE_MINUTES).append(" min. ");
        }

        // Check cooldown
        boolean inCooldown = driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(driverId, OffsetDateTime.now());
        if (inCooldown) {
            eligible = false;
            reasons.append("Driver is in cooldown period. ");
        }

        return Map.of(
                "driverId", driverId,
                "eligible", eligible,
                "rating", rating,
                "onlineMinutesToday", mins,
                "inCooldown", inCooldown,
                "reasons", reasons.toString().trim()
        );
    }

    /**
     * Eligibility check that also enforces the maximum driver-to-pickup distance.
     * When all four coordinate parameters are non-null the Haversine distance is
     * computed; if it exceeds {@value #MAX_DRIVER_MILES} miles the driver is
     * marked ineligible and the reason string is updated accordingly.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> checkDriverEligibility(UUID driverId,
                                                       Double driverLat, Double driverLng,
                                                       Double orderLat, Double orderLng) {
        // Delegate to the existing 1-arg check for the base criteria
        Map<String, Object> base = checkDriverEligibility(driverId);

        // If all four coordinates are present, apply the distance constraint
        if (driverLat == null || driverLng == null || orderLat == null || orderLng == null) {
            return base;
        }

        double distanceMiles = GeoUtils.haversineDistanceMiles(driverLat, driverLng, orderLat, orderLng);
        if (distanceMiles <= MAX_DRIVER_MILES) {
            return base;
        }

        // Driver is too far — build an updated result map
        boolean wasEligible = (boolean) base.get("eligible");
        String existingReasons = (String) base.get("reasons");
        String distanceReason = String.format(
                "Driver is %.2f miles from pickup, max %.1f miles.", distanceMiles, MAX_DRIVER_MILES);
        String combinedReasons = existingReasons.isEmpty()
                ? distanceReason
                : existingReasons + " " + distanceReason;

        Map<String, Object> updated = new LinkedHashMap<>(base);
        updated.put("eligible", false);
        updated.put("reasons", combinedReasons);
        return updated;
    }

    @Transactional(readOnly = true)
    public List<ZoneQueue> getQueueForZone(UUID zoneId) {
        return zoneQueueRepository.findByZoneIdAndStatusOrderByQueuePosition(zoneId, "WAITING");
    }

    /**
     * Reassigns a forced-dispatch order to a different driver.
     * <p>Requires that the order is currently ASSIGNED (not yet accepted) and
     * that at least 30 minutes have elapsed since the original assignment.
     */
    @Transactional
    public DispatchOrder reassignOrder(UUID orderId, Integer entityVersion, UUID newDriverId, User actor) {
        DispatchOrder order = loadOrderWithTenantCheck(orderId, actor);

        requireVersion(entityVersion, order.getVersion(), "DispatchOrder", orderId,
                Map.of("status", order.getStatus().name()));

        if (order.getStatus() != OrderStatus.ASSIGNED) {
            throw new BusinessException(
                    "Only ASSIGNED orders can be reassigned; current status: " + order.getStatus(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (order.getAssignedAt() != null
                && order.getAssignedAt().plusMinutes(COOLDOWN_MINUTES).isAfter(OffsetDateTime.now())) {
            throw new BusinessException(
                    "Cannot reassign before 30 minutes have elapsed since original assignment",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        order.setAssignedDriverId(newDriverId);
        order.setAssignedAt(OffsetDateTime.now());
        DispatchOrder saved = dispatchOrderRepository.save(order);

        log.info("ORDER_REASSIGNED orderId={} newDriverId={}", orderId, newDriverId);

        auditService.log(actor.getId(), "DISPATCH_ORDER_REASSIGNED",
                "dispatch_orders/" + orderId,
                Map.of("newDriverId", newDriverId.toString()));

        return saved;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DispatchOrder loadOrderWithTenantCheck(UUID orderId, User actor) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        if (orgId != null) {
            return dispatchOrderRepository.findByIdAndOrganizationId(orderId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("DispatchOrder", orderId));
        }
        return dispatchOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("DispatchOrder", orderId));
    }

    private void notifyDriver(UUID driverId, String type, String title, String body, String entityRef) {
        try {
            notificationService.createNotification(driverId, type, title, body, entityRef);
        } catch (Exception e) {
            log.warn("Failed to send dispatch event notification: {}", e.getMessage());
        }
    }

    /**
     * Guards forced-dispatch acceptance integrity.
     * <p>
     * Any order that was force-assigned (forcedFlag == true), has mode
     * DISPATCHER_ASSIGNED, <em>or</em> is currently in ASSIGNED status with
     * a pre-set assignedDriverId <strong>must</strong> only be accepted by
     * the designated driver.  This prevents hijacking by another driver.
     */
    private static void requireForcedDispatchDriverMatch(DispatchOrder order, User driver) {
        boolean isForced = order.isForcedFlag()
                || order.getMode() == OrderMode.DISPATCHER_ASSIGNED
                || order.getStatus() == OrderStatus.ASSIGNED;
        if (isForced
                && order.getAssignedDriverId() != null
                && !order.getAssignedDriverId().equals(driver.getId())) {
            throw new BusinessException(
                    "Only the designated driver may accept a forced-dispatch order",
                    HttpStatus.FORBIDDEN);
        }
    }

    private static void requireVersion(Integer requestedVersion, Integer serverVersion,
                                        String entityType, UUID entityId,
                                        Map<String, Object> stateSummary) {
        if (requestedVersion == null || !requestedVersion.equals(serverVersion)) {
            throw new VersionConflictException(entityType, entityId, serverVersion, stateSummary);
        }
    }
}
