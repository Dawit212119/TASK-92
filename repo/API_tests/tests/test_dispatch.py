"""
Dispatch tests:
  - create order (success, validation errors, missing idempotency key)
  - get order
  - accept order (success, version conflict, missing X-Entity-Version)
  - reject order (success with reason enum, missing rejectionReason → 422,
                  invalid rejectionReason → 400, missing idempotency key)
  - driver eligibility endpoint
"""
import pytest
from conftest import (api, ADMIN, DISPATCHER, DRIVER1, CLERK, idem,
                      ZONE_A_ID, DRIVER1_ID)


def make_order(a=None, zone_id=None):
    return api("POST", "/api/v1/dispatch/orders",
               a=a or DISPATCHER,
               json={"zoneId": zone_id or ZONE_A_ID,
                     "mode": "GRAB",
                     "pickupLat": 37.77, "pickupLng": -122.41,
                     "dropoffLat": 37.78, "dropoffLng": -122.42},
               headers={"Idempotency-Key": idem()})


class TestCreateOrder:
    def test_create_order_returns_201(self):
        r = make_order()
        assert r.status_code == 201
        body = r.json()
        assert body["status"] in ("PENDING", "QUEUED")
        assert body["zoneId"] == ZONE_A_ID

    def test_create_order_missing_zone_id_returns_422(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=DISPATCHER,
                json={"mode": "GRAB",
                      "pickupLat": 37.77, "pickupLng": -122.41,
                      "dropoffLat": 37.78, "dropoffLng": -122.42},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 422
        assert r.json()["error_code"] == "VALIDATION_FAILED"

    def test_create_order_missing_idempotency_key_returns_400(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=DISPATCHER,
                json={"zoneId": ZONE_A_ID, "mode": "GRAB",
                      "pickupLat": 37.77, "pickupLng": -122.41,
                      "dropoffLat": 37.78, "dropoffLng": -122.42})
        assert r.status_code == 400
        assert r.json()["error_code"] == "IDEMPOTENCY_KEY_REQUIRED"

    def test_create_order_invalid_zone_returns_404(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=DISPATCHER,
                json={"zoneId": "00000000-0000-0000-0000-000000009999",
                      "mode": "GRAB",
                      "pickupLat": 37.77, "pickupLng": -122.41,
                      "dropoffLat": 37.78, "dropoffLng": -122.42},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 404

    def test_create_order_missing_pickup_coords_returns_422(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=DISPATCHER,
                json={"zoneId": ZONE_A_ID, "mode": "GRAB",
                      "dropoffLat": 37.78, "dropoffLng": -122.42},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 422
        assert r.json()["error_code"] == "VALIDATION_FAILED"

    def test_create_order_out_of_range_lat_returns_422(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=DISPATCHER,
                json={"zoneId": ZONE_A_ID, "mode": "GRAB",
                      "pickupLat": 999, "pickupLng": -122.41,
                      "dropoffLat": 37.78, "dropoffLng": -122.42},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 422

    def test_idempotency_replay_returns_same_order(self):
        key = idem()
        payload = {"zoneId": ZONE_A_ID, "mode": "GRAB",
                   "pickupLat": 37.77, "pickupLng": -122.41,
                   "dropoffLat": 37.78, "dropoffLng": -122.42}
        headers = {"Idempotency-Key": key}
        r1 = api("POST", "/api/v1/dispatch/orders", a=DISPATCHER,
                 json=payload, headers=headers)
        r2 = api("POST", "/api/v1/dispatch/orders", a=DISPATCHER,
                 json=payload, headers=headers)
        assert r1.status_code == 201
        assert r2.status_code == 201
        assert r1.json()["id"] == r2.json()["id"]


class TestGetOrder:
    def test_get_order_by_id(self):
        order = make_order().json()
        r = api("GET", f"/api/v1/dispatch/orders/{order['id']}", a=DISPATCHER)
        assert r.status_code == 200
        assert r.json()["id"] == order["id"]

    def test_get_nonexistent_order_returns_404(self):
        r = api("GET",
                "/api/v1/dispatch/orders/00000000-0000-0000-0000-000000009999",
                a=DISPATCHER)
        assert r.status_code == 404


class TestAcceptOrder:
    # Driver coordinates close to the order's pickup location (37.77, -122.41)
    # — distance ≈ 0 miles, well within the 3-mile limit.
    DRIVER_COORDS = {"driverLat": 37.77, "driverLng": -122.41}

    def _pending_order(self):
        r = make_order()
        assert r.status_code == 201
        return r.json()

    def test_accept_order_succeeds(self):
        order = self._pending_order()
        if order["status"] == "QUEUED":
            pytest.skip("Zone at capacity — order queued, cannot accept")
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/accept",
                a=DRIVER1,
                json=self.DRIVER_COORDS,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": str(order["version"])})
        assert r.status_code == 200
        assert r.json()["status"] == "ACCEPTED"

    def test_accept_order_version_conflict_returns_409(self):
        order = self._pending_order()
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/accept",
                a=DRIVER1,
                json=self.DRIVER_COORDS,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": "9999"})
        assert r.status_code == 409
        assert r.json()["error_code"] == "VERSION_CONFLICT"

    def test_accept_order_missing_driver_coords_returns_400(self):
        """Driver coordinates are mandatory; omitting them must return 400."""
        order = self._pending_order()
        if order["status"] == "QUEUED":
            pytest.skip("Zone at capacity — order queued, cannot accept")
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/accept",
                a=DRIVER1,
                # No json body — missing driverLat/driverLng
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": str(order["version"])})
        assert r.status_code == 400

    def test_accept_order_missing_entity_version_returns_400(self):
        order = self._pending_order()
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/accept",
                a=DRIVER1,
                json=self.DRIVER_COORDS,
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 400


class TestRejectOrder:
    def _pending_order(self):
        r = make_order()
        assert r.status_code == 201
        return r.json()

    def test_reject_order_with_valid_reason_succeeds(self):
        order = self._pending_order()
        if order["status"] == "QUEUED":
            pytest.skip("Zone at capacity — order queued")
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/reject",
                a=DRIVER1,
                json={"rejectionReason": "TOO_FAR",
                      "entityVersion": order["version"]},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 200
        assert r.json()["status"] == "PENDING"   # reset for reassignment

    def test_reject_order_missing_rejection_reason_returns_422(self):
        order = self._pending_order()
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/reject",
                a=DRIVER1,
                json={"entityVersion": order["version"]},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 422
        assert r.json()["error_code"] == "VALIDATION_FAILED"

    def test_reject_order_invalid_reason_returns_400(self):
        order = self._pending_order()
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/reject",
                a=DRIVER1,
                json={"rejectionReason": "NOT_A_VALID_REASON",
                      "entityVersion": order["version"]},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 400

    def test_reject_order_version_conflict_returns_409(self):
        order = self._pending_order()
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/reject",
                a=DRIVER1,
                json={"rejectionReason": "TOO_FAR", "entityVersion": 9999},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 409

    def test_reject_order_missing_idempotency_key_returns_400(self):
        order = self._pending_order()
        r = api("POST", f"/api/v1/dispatch/orders/{order['id']}/reject",
                a=DRIVER1,
                json={"rejectionReason": "TOO_FAR",
                      "entityVersion": order["version"]})
        assert r.status_code == 400


class TestDriverEligibility:
    def test_eligibility_endpoint_returns_map(self):
        r = api("GET", f"/api/v1/drivers/{DRIVER1_ID}/eligibility",
                a=DISPATCHER)
        assert r.status_code == 200
        body = r.json()
        assert "eligible" in body
        assert "rating" in body
        assert "onlineMinutesToday" in body
        assert "inCooldown" in body

    def test_eligibility_nonexistent_driver_returns_404(self):
        r = api("GET",
                "/api/v1/drivers/00000000-0000-0000-0000-000000009999/eligibility",
                a=DISPATCHER)
        assert r.status_code == 404
