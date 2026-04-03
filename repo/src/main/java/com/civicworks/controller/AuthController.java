package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final AuthUtils authUtils;

    public AuthController(AuthenticationManager authenticationManager, AuthUtils authUtils) {
        this.authenticationManager = authenticationManager;
        this.authUtils = authUtils;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        MDC.put("username", request.username());
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));

            User user = authUtils.resolveUser(auth);
            MDC.put("userId", user.getId().toString());
            log.info("LOGIN_SUCCESS user={} role={}", user.getUsername(), user.getRole());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", user.getId());
            body.put("username", user.getUsername());
            body.put("role", user.getRole());
            body.put("organizationId", user.getOrganization() != null ? user.getOrganization().getId() : null);
            body.put("status", user.getStatus());
            return ResponseEntity.ok(body);

        } catch (AuthenticationException ex) {
            log.warn("LOGIN_FAILED username={}", request.username());
            throw ex; // mapped to 401 by GlobalExceptionHandler
        } finally {
            MDC.remove("userId");
            MDC.remove("username");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            log.info("LOGOUT user={}", auth.getName());
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        User user = authUtils.resolveUser(authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("role", user.getRole());
        body.put("organizationId", user.getOrganization() != null ? user.getOrganization().getId() : null);
        body.put("status", user.getStatus());
        return ResponseEntity.ok(body);
    }
}
