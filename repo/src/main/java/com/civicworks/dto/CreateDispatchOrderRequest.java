package com.civicworks.dto;

import com.civicworks.domain.enums.OrderMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public class CreateDispatchOrderRequest {

    @NotNull(message = "zoneId is required")
    private UUID zoneId;

    @NotNull(message = "mode is required")
    private OrderMode mode = OrderMode.GRAB;

    @NotNull(message = "pickupLat is required")
    @DecimalMin(value = "-90.0", message = "pickupLat must be between -90 and 90")
    @DecimalMax(value = "90.0",  message = "pickupLat must be between -90 and 90")
    private BigDecimal pickupLat;

    @NotNull(message = "pickupLng is required")
    @DecimalMin(value = "-180.0", message = "pickupLng must be between -180 and 180")
    @DecimalMax(value = "180.0",  message = "pickupLng must be between -180 and 180")
    private BigDecimal pickupLng;

    @DecimalMin(value = "-90.0", message = "dropoffLat must be between -90 and 90")
    @DecimalMax(value = "90.0",  message = "dropoffLat must be between -90 and 90")
    private BigDecimal dropoffLat;

    @DecimalMin(value = "-180.0", message = "dropoffLng must be between -180 and 180")
    @DecimalMax(value = "180.0",  message = "dropoffLng must be between -180 and 180")
    private BigDecimal dropoffLng;

    // For forced dispatch (DISPATCHER_ASSIGNED)
    private UUID assignedDriverId;
    private boolean forcedFlag = false;

    public UUID getZoneId() { return zoneId; }
    public void setZoneId(UUID zoneId) { this.zoneId = zoneId; }

    public OrderMode getMode() { return mode; }
    public void setMode(OrderMode mode) { this.mode = mode; }

    public BigDecimal getPickupLat() { return pickupLat; }
    public void setPickupLat(BigDecimal pickupLat) { this.pickupLat = pickupLat; }

    public BigDecimal getPickupLng() { return pickupLng; }
    public void setPickupLng(BigDecimal pickupLng) { this.pickupLng = pickupLng; }

    public BigDecimal getDropoffLat() { return dropoffLat; }
    public void setDropoffLat(BigDecimal dropoffLat) { this.dropoffLat = dropoffLat; }

    public BigDecimal getDropoffLng() { return dropoffLng; }
    public void setDropoffLng(BigDecimal dropoffLng) { this.dropoffLng = dropoffLng; }

    public UUID getAssignedDriverId() { return assignedDriverId; }
    public void setAssignedDriverId(UUID assignedDriverId) { this.assignedDriverId = assignedDriverId; }

    public boolean isForcedFlag() { return forcedFlag; }
    public void setForcedFlag(boolean forcedFlag) { this.forcedFlag = forcedFlag; }
}
