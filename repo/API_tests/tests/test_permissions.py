"""
Role-based access control tests.

Each test verifies that a principal with an insufficient role receives 403
when hitting an endpoint that requires a different role.

Coverage matrix (role → forbidden endpoint):
  DRIVER     → billing endpoints, content creation, moderation
  AUDITOR    → mutations (settlements, dispatch, content create)
  EDITOR     → billing/dispatch/moderation
  DISPATCHER → billing/moderation/content creation
  CLERK      → dispatch/moderation
  No auth    → any authenticated endpoint → 401
"""
import pytest
from conftest import (api, DRIVER1, AUDITOR, EDITOR, DISPATCHER, CLERK,
                      MODERATOR, ADMIN, idem,
                      ZONE_A_ID, BILL_ID, CONTENT_DRAFT_ID)


# ── 401 — unauthenticated ─────────────────────────────────────────────────────

class TestUnauthenticated:
    def test_content_list_requires_auth(self):
        r = api("GET", "/api/v1/content/items")
        assert r.status_code == 401

    def test_billing_bills_requires_auth(self):
        r = api("GET", "/api/v1/billing/bills")
        assert r.status_code == 401

    def test_dispatch_order_requires_auth(self):
        r = api("GET", f"/api/v1/dispatch/orders/00000000-0000-0000-0000-000000000001")
        assert r.status_code == 401

    def test_audit_requires_auth(self):
        r = api("GET", "/api/v1/audit/logs")
        assert r.status_code == 401


# ── 403 — authenticated but wrong role ───────────────────────────────────────

class TestDriverForbiddenFromBilling:
    def test_driver_cannot_create_fee_item(self):
        r = api("POST", "/api/v1/billing/fee-items",
                a=DRIVER1,
                json={"code": "DRV-FEE", "calculationType": "FLAT",
                      "rateCents": 100, "taxableFlag": False},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403
        assert r.json()["error_code"] == "FORBIDDEN"

    def test_driver_cannot_list_bills(self):
        r = api("GET", "/api/v1/billing/bills", a=DRIVER1)
        assert r.status_code == 403

    def test_driver_cannot_create_billing_run(self):
        r = api("POST", "/api/v1/billing/billing-runs",
                a=DRIVER1,
                json={"cycleDate": "2025-01-01", "billingCycle": "MONTHLY"},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_driver_cannot_create_content(self):
        r = api("POST", "/api/v1/content/items",
                a=DRIVER1,
                json={"type": "NEWS", "title": "Driver News"},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_driver_cannot_moderate_comments(self):
        r = api("POST",
                f"/api/v1/comments/00000000-0000-0000-0000-000000000001/moderate",
                a=DRIVER1,
                json={"moderationState": "APPROVED", "reason": "OK"})
        assert r.status_code == 403


class TestAuditorForbiddenFromMutations:
    def test_auditor_cannot_create_content(self):
        r = api("POST", "/api/v1/content/items",
                a=AUDITOR,
                json={"type": "NEWS", "title": "Auditor Attempt"},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_auditor_cannot_create_dispatch_order(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=AUDITOR,
                json={"zoneId": ZONE_A_ID, "mode": "GRAB",
                      "pickupLat": 1.0, "pickupLng": 1.0,
                      "dropoffLat": 1.1, "dropoffLng": 1.1},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_auditor_cannot_create_settlement(self):
        r = api("POST", f"/api/v1/billing/bills/{BILL_ID}/settlements",
                a=AUDITOR,
                json={"settlementMode": "FULL", "paymentMethod": "CASH",
                      "entityVersion": 0},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_auditor_can_read_bills(self):
        r = api("GET", "/api/v1/billing/bills", a=AUDITOR)
        assert r.status_code == 200

    def test_auditor_can_read_audit_logs(self):
        r = api("GET", "/api/v1/audit/logs", a=AUDITOR)
        assert r.status_code == 200


class TestEditorForbiddenFromBillingAndDispatch:
    def test_editor_cannot_list_bills(self):
        r = api("GET", "/api/v1/billing/bills", a=EDITOR)
        assert r.status_code == 403

    def test_editor_cannot_create_dispatch_order(self):
        r = api("POST", "/api/v1/dispatch/orders",
                a=EDITOR,
                json={"zoneId": ZONE_A_ID, "mode": "GRAB",
                      "pickupLat": 1.0, "pickupLng": 1.0,
                      "dropoffLat": 1.1, "dropoffLng": 1.1},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_editor_cannot_add_sensitive_words(self):
        r = api("POST", "/api/v1/moderation/sensitive-words",
                a=EDITOR,
                json={"word": "forbiddenword"})
        assert r.status_code == 403


class TestClerkForbiddenFromDispatchAndModeration:
    def test_clerk_cannot_accept_order(self):
        r = api("POST",
                "/api/v1/dispatch/orders/00000000-0000-0000-0000-000000000001/accept",
                a=CLERK,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": "0"})
        assert r.status_code == 403

    def test_clerk_cannot_add_sensitive_words(self):
        r = api("POST", "/api/v1/moderation/sensitive-words",
                a=CLERK,
                json={"word": "clerkword"})
        assert r.status_code == 403

    def test_clerk_cannot_moderate_comments(self):
        r = api("POST",
                f"/api/v1/comments/00000000-0000-0000-0000-000000000001/moderate",
                a=CLERK,
                json={"moderationState": "APPROVED", "reason": "OK"})
        assert r.status_code == 403


class TestModeratorForbiddenFromBilling:
    def test_moderator_cannot_create_billing_run(self):
        r = api("POST", "/api/v1/billing/billing-runs",
                a=MODERATOR,
                json={"cycleDate": "2025-01-01", "billingCycle": "MONTHLY"},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403

    def test_moderator_cannot_create_settlement(self):
        r = api("POST", f"/api/v1/billing/bills/{BILL_ID}/settlements",
                a=MODERATOR,
                json={"settlementMode": "FULL", "paymentMethod": "CASH",
                      "entityVersion": 0},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 403
