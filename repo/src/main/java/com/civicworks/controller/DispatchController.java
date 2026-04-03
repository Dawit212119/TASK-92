package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.config.IdempotencyGuard;
import com.civicworks.domain.entity.DispatchOrder;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.entity.ZoneQueue;
import com.civicworks.dto.AcceptOrderRequest;
import com.civicworks.dto.CreateDispatchOrderRequest;
import com.civicworks.dto.ReassignOrderRequest;
import com.civicworks.dto.RejectOrderRequest;
import com.civicworks.service.DispatchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DispatchController {

    private static final Logger log = LoggerFactory.getLogger(DispatchController.class);

    private final DispatchService dispatchService;
    private final AuthUtils authUtils;
    private final IdempotencyGuard idempotencyGuard;

    public DispatchController(DispatchService dispatchService,
                               AuthUtils authUtils,
                               IdempotencyGuard idempotencyGuard) {
        this.dispatchService = dispatchService;
        this.authUtils = authUtils;
        this.idempotencyGuard = idempotencyGuard;
    }

    @PostMapping("/dispatch/orders")
    @PreAuthorize("hasRole('DISPATCHER') or hasRole('DRIVER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DispatchOrder> createOrder(
            @Valid @RequestBody CreateDispatchOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_DISPATCH_ORDER",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(dispatchService.createOrder(request, actor)));
    }

    @GetMapping("/dispatch/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DispatchOrder> getOrder(@PathVariable UUID orderId,
                                                    Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(dispatchService.findById(orderId, actor));
    }

    @PostMapping("/dispatch/orders/{orderId}/accept")
    @PreAuthorize("hasRole('DRIVER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DispatchOrder> acceptOrder(
            @PathVariable UUID orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Entity-Version") Integer entityVersion,
            @RequestBody(required = false) AcceptOrderRequest request,
            Authentication authentication) {
        User driver = authUtils.resolveUser(authentication);
        MDC.put("orderId", orderId.toString());
        MDC.put("driverId", driver.getId().toString());
        try {
            log.info("ORDER_ACCEPT_REQUEST orderId={} driverId={}", orderId, driver.getId());
            return idempotencyGuard.execute(idempotencyKey, driver.getId(), "ACCEPT_ORDER",
                    () -> ResponseEntity.ok(
                            dispatchService.acceptOrder(orderId, entityVersion, driver, request)));
        } finally {
            MDC.remove("orderId");
            MDC.remove("driverId");
        }
    }

    @PostMapping("/dispatch/orders/{orderId}/reject")
    @PreAuthorize("hasRole('DRIVER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DispatchOrder> rejectOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody RejectOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User driver = authUtils.resolveUser(authentication);
        MDC.put("orderId", orderId.toString());
        MDC.put("driverId", driver.getId().toString());
        try {
            log.info("ORDER_REJECT_REQUEST orderId={} driverId={} reason={}", orderId, driver.getId(), request.getRejectionReason());
            return idempotencyGuard.execute(idempotencyKey, driver.getId(), "REJECT_ORDER",
                    () -> ResponseEntity.ok(dispatchService.rejectOrder(orderId, request, driver)));
        } finally {
            MDC.remove("orderId");
            MDC.remove("driverId");
        }
    }

    @PostMapping("/dispatch/orders/{orderId}/reassign")
    @PreAuthorize("hasRole('DISPATCHER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DispatchOrder> reassignOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody ReassignOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "REASSIGN_ORDER",
                () -> ResponseEntity.ok(
                        dispatchService.reassignOrder(orderId, request.getEntityVersion(),
                                request.getNewDriverId(), actor)));
    }

    @GetMapping("/dispatch/queues")
    @PreAuthorize("hasRole('DISPATCHER') or hasRole('DRIVER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<ZoneQueue>> getQueue(@RequestParam UUID zoneId) {
        return ResponseEntity.ok(dispatchService.getQueueForZone(zoneId));
    }

    @GetMapping("/drivers/{driverId}/eligibility")
    @PreAuthorize("hasRole('DISPATCHER') or hasRole('DRIVER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> getDriverEligibility(@PathVariable UUID driverId) {
        return ResponseEntity.ok(dispatchService.checkDriverEligibility(driverId));
    }
}
