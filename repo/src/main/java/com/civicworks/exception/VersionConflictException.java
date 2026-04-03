package com.civicworks.exception;

import java.util.Map;
import java.util.UUID;

/**
 * Thrown when a client-supplied entityVersion does not match the current
 * server-side version of the resource.  The handler in GlobalExceptionHandler
 * serialises the fields here into the 409 response body so the caller has
 * enough context to refresh and retry.
 */
public class VersionConflictException extends RuntimeException {

    private final String entityType;
    private final UUID entityId;
    private final Integer serverVersion;
    private final Map<String, Object> stateSummary;

    public VersionConflictException(String entityType, UUID entityId,
                                     Integer serverVersion,
                                     Map<String, Object> stateSummary) {
        super("Version conflict on " + entityType + " " + entityId
                + ": server version is " + serverVersion);
        this.entityType = entityType;
        this.entityId = entityId;
        this.serverVersion = serverVersion;
        this.stateSummary = stateSummary;
    }

    public String getEntityType()            { return entityType; }
    public UUID   getEntityId()              { return entityId; }
    public Integer getServerVersion()        { return serverVersion; }
    public Map<String, Object> getStateSummary() { return stateSummary; }
}
