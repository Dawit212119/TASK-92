"""
Shared fixtures and helpers for CivicWorks API tests.

Credentials (seeded by V1 migration, password = admin123):
  admin       → SYSTEM_ADMIN
  editor      → CONTENT_EDITOR
  clerk       → BILLING_CLERK
  driver1     → DRIVER
  moderator   → MODERATOR
  dispatcher  → DISPATCHER
  auditor     → AUDITOR
"""
import os
import uuid
import requests
import pytest
from requests.auth import HTTPBasicAuth

# ── Base URL ────────────────────────────────────────────────────────────────
BASE_URL = os.environ.get("BASE_URL", "http://localhost:18080").rstrip("/")

# ── Well-known seed UUIDs (from V1 migration + seed.sql) ────────────────────
ORG_ID         = "00000000-0000-0000-0000-000000000001"
ADMIN_ID       = "00000000-0000-0000-0000-000000000002"
EDITOR_ID      = "00000000-0000-0000-0000-000000000003"
CLERK_ID       = "00000000-0000-0000-0000-000000000004"
DRIVER1_ID     = "00000000-0000-0000-0000-000000000005"
MODERATOR_ID   = "00000000-0000-0000-0000-000000000006"
DISPATCHER_ID  = "00000000-0000-0000-0000-000000000007"
AUDITOR_ID     = "00000000-0000-0000-0000-000000000008"
ZONE_A_ID      = "00000000-0000-0000-0000-000000000010"
ACCOUNT_ID     = "00000000-0000-0000-0000-000000000020"
BILL_ID        = "00000000-0000-0000-0000-000000000030"  # OPEN, $100, past grace period
BILL_SPLIT_ID  = "00000000-0000-0000-0000-000000000031"  # OPEN, $90, for split tests
CONTENT_DRAFT_ID     = "00000000-0000-0000-0000-000000000040"
CONTENT_PUBLISHED_ID = "00000000-0000-0000-0000-000000000041"


# ── Auth helpers ─────────────────────────────────────────────────────────────
def auth(username: str, password: str = "admin123") -> HTTPBasicAuth:
    return HTTPBasicAuth(username, password)


# Auth shortcuts
ADMIN      = auth("admin")
EDITOR     = auth("editor")
CLERK      = auth("clerk")
DRIVER1    = auth("driver1")
MODERATOR  = auth("moderator")
DISPATCHER = auth("dispatcher")
AUDITOR    = auth("auditor")


# ── Request helpers ───────────────────────────────────────────────────────────
def api(method: str, path: str, *, a=None, json=None, headers=None, params=None):
    """Thin wrapper around requests so tests stay concise."""
    url = f"{BASE_URL}{path}"
    h = headers or {}
    return requests.request(method, url, auth=a, json=json, headers=h,
                            params=params, timeout=15)


def idem() -> str:
    """Return a unique idempotency key for each call site."""
    return str(uuid.uuid4())


# ── Fixtures ─────────────────────────────────────────────────────────────────
@pytest.fixture(scope="session")
def base_url():
    return BASE_URL


@pytest.fixture
def unique_key():
    """Fresh idempotency key for each test."""
    return idem()


@pytest.fixture(scope="session")
def created_content_item():
    """Creates a fresh DRAFT content item for tests that need one."""
    r = api("POST", "/api/v1/content/items",
            a=EDITOR,
            json={"type": "NEWS", "title": "Fixture Article"},
            headers={"Idempotency-Key": idem()})
    assert r.status_code == 201, f"Fixture setup failed: {r.text}"
    return r.json()


@pytest.fixture(scope="session")
def created_dispatch_order():
    """Creates a fresh PENDING dispatch order for driver tests."""
    r = api("POST", "/api/v1/dispatch/orders",
            a=DISPATCHER,
            json={"zoneId": ZONE_A_ID, "mode": "GRAB",
                  "pickupLat": 37.77, "pickupLng": -122.41,
                  "dropoffLat": 37.78, "dropoffLng": -122.42},
            headers={"Idempotency-Key": idem()})
    assert r.status_code == 201, f"Fixture setup failed: {r.text}"
    return r.json()
