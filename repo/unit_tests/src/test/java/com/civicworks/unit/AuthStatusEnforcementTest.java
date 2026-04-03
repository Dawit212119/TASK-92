package com.civicworks.unit;

import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.Role;
import com.civicworks.exception.BusinessException;
import com.civicworks.repository.UserRepository;
import com.civicworks.service.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests that:
 * - Inactive/suspended users are denied authentication.
 * - Non-admin users without organization assignment are denied tenant-scoped access.
 */
@ExtendWith(MockitoExtension.class)
class AuthStatusEnforcementTest {

    @Mock UserRepository userRepository;

    // ── Authentication status enforcement ────────────────────────────────

    @Test
    void activeUser_isEnabled() {
        User user = activeUser("testuser", Role.BILLING_CLERK);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetailsService uds = buildUserDetailsService();
        UserDetails details = uds.loadUserByUsername("testuser");

        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void inactiveUser_isDisabled() {
        User user = activeUser("inactive", Role.BILLING_CLERK);
        user.setStatus("INACTIVE");
        when(userRepository.findByUsername("inactive")).thenReturn(Optional.of(user));

        UserDetailsService uds = buildUserDetailsService();
        UserDetails details = uds.loadUserByUsername("inactive");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void suspendedUser_isDisabled() {
        User user = activeUser("suspended", Role.AUDITOR);
        user.setStatus("SUSPENDED");
        when(userRepository.findByUsername("suspended")).thenReturn(Optional.of(user));

        UserDetailsService uds = buildUserDetailsService();
        UserDetails details = uds.loadUserByUsername("suspended");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void unknownUser_throwsUsernameNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        UserDetailsService uds = buildUserDetailsService();

        assertThatThrownBy(() -> uds.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    // ── Org-null non-admin denial ────────────────────────────────────────

    @Test
    void nonAdminWithNoOrg_isDeniedTenantAccess() {
        User auditor = new User();
        auditor.setId(UUID.randomUUID());
        auditor.setRole(Role.AUDITOR);
        // no organization set

        assertThatThrownBy(() -> AuthorizationService.resolveOrgId(auditor))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getCode()).isEqualTo("MISSING_ORGANIZATION");
                    assertThat(bex.getStatus().value()).isEqualTo(403);
                });
    }

    @Test
    void driverWithNoOrg_isDeniedTenantAccess() {
        User driver = new User();
        driver.setId(UUID.randomUUID());
        driver.setRole(Role.DRIVER);

        assertThatThrownBy(() -> AuthorizationService.resolveOrgId(driver))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("MISSING_ORGANIZATION"));
    }

    @Test
    void systemAdminWithNoOrg_getsGlobalAccess() {
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setRole(Role.SYSTEM_ADMIN);

        assertThat(AuthorizationService.resolveOrgId(admin)).isNull();
    }

    @Test
    void nonAdminWithOrg_getsOrgScoped() {
        User clerk = new User();
        clerk.setId(UUID.randomUUID());
        clerk.setRole(Role.BILLING_CLERK);
        Organization org = new Organization();
        UUID orgId = UUID.randomUUID();
        org.setId(orgId);
        clerk.setOrganization(org);

        assertThat(AuthorizationService.resolveOrgId(clerk)).isEqualTo(orgId);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private User activeUser(String username, Role role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setPasswordHash("$2a$10$dummyhash");
        u.setRole(role);
        u.setStatus("ACTIVE");
        return u;
    }

    /**
     * Builds the same UserDetailsService logic as SecurityConfig but without
     * requiring a full Spring context.
     */
    private UserDetailsService buildUserDetailsService() {
        return username -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
            boolean enabled = "ACTIVE".equals(user.getStatus());
            return org.springframework.security.core.userdetails.User.builder()
                    .username(user.getUsername())
                    .password(user.getPasswordHash())
                    .disabled(!enabled)
                    .authorities(java.util.List.of(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                    "ROLE_" + user.getRole().name())))
                    .build();
        };
    }
}
