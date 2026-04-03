package com.civicworks.dto;

import java.util.UUID;

public class LoginResponse {

    private UUID userId;
    private String username;
    private String role;
    private UUID organizationId;
    private String status;

    public LoginResponse() {}

    public LoginResponse(UUID userId, String username, String role,
                         UUID organizationId, String status) {
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.organizationId = organizationId;
        this.status = status;
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
