package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.Notification;
import com.civicworks.domain.entity.User;
import com.civicworks.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final AuthUtils authUtils;

    public NotificationController(NotificationService notificationService, AuthUtils authUtils) {
        this.notificationService = notificationService;
        this.authUtils = authUtils;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        Page<Notification> result = notificationService.findForUserPage(
                actor.getId(), PageRequest.of(Math.max(0, page), Math.min(100, size)));
        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "total", result.getTotalElements()
        ));
    }

    @PostMapping("/{notificationId}/ack")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Notification> acknowledgeNotification(@PathVariable UUID notificationId,
                                                                 Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(notificationService.acknowledge(notificationId, actor));
    }
}
