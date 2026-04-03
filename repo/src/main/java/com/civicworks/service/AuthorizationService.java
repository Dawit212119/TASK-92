package com.civicworks.service;

import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.Role;
import com.civicworks.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Centralised object-level authorization enforcement.
 * <p>
 * Every service-layer mutation that accesses an org-scoped entity MUST call
 * one of this service's {@code check*} methods <em>before</em> proceeding.
 * The checks are intentionally in the service layer (not the controller)
 * so they cannot be bypassed by alternative entry points such as schedulers
 * or message listeners.
 *
 * <h3>Bypass rules</h3>
 * <ul>
 *   <li>{@code actor == null} — scheduler / system call → global access</li>
 *   <li>{@code actor.role == SYSTEM_ADMIN} → global access</li>
 *   <li>{@code actor.organization == null} — unscoped user → global access (no org to restrict to)</li>
 * </ul>
 *
 * All other actors are restricted to entities whose {@code organizationId}
 * matches their own.  A mismatch throws {@link ResourceNotFoundException}
 * (returns HTTP 404 to the caller, preventing enumeration).
 */
@Service
public class AuthorizationService {

    /**
     * Resolves the effective org-id for tenant isolation.
     * Returns {@code null} when the caller has global access.
     */
    public static UUID resolveOrgId(User actor) {
        if (actor == null) return null;
        if (actor.getRole() == Role.SYSTEM_ADMIN) return null;
        return actor.getOrganization() != null ? actor.getOrganization().getId() : null;
    }

    /**
     * Asserts that the caller's organisation matches the entity's organisation.
     *
     * @param entityOrgId the organisation id stored on the entity
     * @param actor       the authenticated user
     * @param entityType  label used in the 404 message (e.g. "Bill", "ContentItem")
     * @param entityId    the entity's primary key (used in the 404 message)
     * @throws ResourceNotFoundException if the org ids do not match
     */
    public static void checkOwnership(UUID entityOrgId, User actor,
                                       String entityType, UUID entityId) {
        UUID actorOrgId = resolveOrgId(actor);
        if (actorOrgId == null) return;                       // global access
        if (actorOrgId.equals(entityOrgId)) return;           // same org
        throw new ResourceNotFoundException(entityType, entityId);
    }
}
