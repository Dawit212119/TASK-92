package com.civicworks.dto;

/**
 * Optional request body for the accept-order endpoint.
 * When {@code driverLat} and {@code driverLng} are both provided the server
 * performs a Haversine distance check against the order's pickup coordinates
 * and rejects the acceptance if the driver is more than 3 miles away.
 * If either field is absent the distance check is skipped (a WARN is logged).
 */
public class AcceptOrderRequest {

    private Double driverLat;
    private Double driverLng;

    public AcceptOrderRequest() {}

    public Double getDriverLat() { return driverLat; }
    public void setDriverLat(Double driverLat) { this.driverLat = driverLat; }

    public Double getDriverLng() { return driverLng; }
    public void setDriverLng(Double driverLng) { this.driverLng = driverLng; }
}
