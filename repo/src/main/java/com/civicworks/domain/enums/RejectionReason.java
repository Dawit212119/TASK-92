package com.civicworks.domain.enums;

/**
 * Standardised reasons a driver may give when rejecting a dispatch order.
 * Stored as the enum name (VARCHAR) in the dispatch_orders table.
 */
public enum RejectionReason {
    TOO_FAR,            // Pickup or dropoff location is outside acceptable range
    VEHICLE_ISSUE,      // Mechanical or operational vehicle problem
    PERSONAL_EMERGENCY, // Driver has an urgent personal situation
    OUT_OF_AREA,        // Driver is currently outside the service area
    CUSTOMER_NO_SHOW,   // Customer was not present at the pickup location
    OTHER               // Reason does not fit any specific category
}
