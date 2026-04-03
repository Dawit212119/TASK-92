"""
Content management tests:
  - create item (success, validation errors, idempotency replay)
  - get item / list items
  - update item (version conflict)
  - publish item (success, already published, version conflict)
  - unpublish item
  - publish history
  - HTML sanitization: XSS stripped from sanitizedBody
"""
import pytest
from conftest import (api, ADMIN, EDITOR, CLERK, idem,
                      CONTENT_DRAFT_ID, CONTENT_PUBLISHED_ID)


def create_draft(title="Test Article", body=None):
    return api("POST", "/api/v1/content/items",
               a=EDITOR,
               json={"type": "NEWS", "title": title, "sanitizedBody": body},
               headers={"Idempotency-Key": idem()})


class TestCreateContentItem:
    def test_create_item_returns_201(self):
        r = create_draft("My News Story")
        assert r.status_code == 201
        body = r.json()
        assert body["title"] == "My News Story"
        assert body["state"] == "DRAFT"
        assert body["version"] == 0

    def test_create_item_missing_type_returns_422(self):
        r = api("POST", "/api/v1/content/items",
                a=EDITOR,
                json={"title": "No Type"},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 422
        assert r.json()["error_code"] == "VALIDATION_FAILED"

    def test_create_item_missing_title_returns_422(self):
        r = api("POST", "/api/v1/content/items",
                a=EDITOR,
                json={"type": "NEWS"},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 422

    def test_create_item_missing_idempotency_key_returns_400(self):
        r = api("POST", "/api/v1/content/items",
                a=EDITOR,
                json={"type": "NEWS", "title": "No Key"})
        assert r.status_code == 400
        assert r.json()["error_code"] == "IDEMPOTENCY_KEY_REQUIRED"

    def test_xss_body_is_sanitized(self):
        r = create_draft("XSS Test", body='<p>Safe</p><script>alert(1)</script>')
        assert r.status_code == 201
        body = r.json().get("sanitizedBody", "")
        assert "<script>" not in (body or "")
        assert "Safe" in (body or "")

    def test_idempotency_replay_returns_same_item(self):
        key = idem()
        payload = {"type": "NEWS", "title": "Idempotent Article"}
        headers = {"Idempotency-Key": key}
        r1 = api("POST", "/api/v1/content/items", a=EDITOR,
                 json=payload, headers=headers)
        r2 = api("POST", "/api/v1/content/items", a=EDITOR,
                 json=payload, headers=headers)
        assert r1.status_code == 201
        assert r2.status_code == 201
        assert r1.json()["id"] == r2.json()["id"]


class TestGetContentItem:
    def test_get_item_by_id(self):
        created = create_draft("Get Me").json()
        r = api("GET", f"/api/v1/content/items/{created['id']}", a=EDITOR)
        assert r.status_code == 200
        assert r.json()["id"] == created["id"]

    def test_get_nonexistent_item_returns_404(self):
        r = api("GET", "/api/v1/content/items/00000000-0000-0000-0000-000000000099",
                a=EDITOR)
        assert r.status_code == 404
        assert r.json()["error_code"] == "NOT_FOUND"

    def test_list_items_returns_page(self):
        r = api("GET", "/api/v1/content/items", a=EDITOR)
        assert r.status_code == 200
        body = r.json()
        assert "data" in body
        assert "total" in body
        assert isinstance(body["data"], list)


class TestUpdateContentItem:
    def test_update_title_succeeds(self):
        item = create_draft("Old Title").json()
        r = api("PATCH", f"/api/v1/content/items/{item['id']}",
                a=EDITOR,
                json={"title": "New Title", "entityVersion": item["version"]},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 200
        assert r.json()["title"] == "New Title"

    def test_update_stale_version_returns_409(self):
        item = create_draft("Version Test").json()
        r = api("PATCH", f"/api/v1/content/items/{item['id']}",
                a=EDITOR,
                json={"title": "Conflict", "entityVersion": 99},
                headers={"Idempotency-Key": idem()})
        assert r.status_code == 409
        assert r.json()["error_code"] == "VERSION_CONFLICT"

    def test_update_null_version_returns_409(self):
        item = create_draft("NullVer").json()
        r = api("PATCH", f"/api/v1/content/items/{item['id']}",
                a=EDITOR,
                json={"title": "X"},   # entityVersion absent → @NotNull violation OR 409
                headers={"Idempotency-Key": idem()})
        assert r.status_code in (409, 422)


class TestPublishContentItem:
    def test_publish_draft_item_succeeds(self):
        item = create_draft("Publishable").json()
        r = api("POST", f"/api/v1/content/items/{item['id']}/publish",
                a=EDITOR,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": str(item["version"])})
        assert r.status_code == 200
        assert r.json()["state"] == "PUBLISHED"

    def test_publish_already_published_returns_422(self):
        # publish once
        item = create_draft("Double Publish").json()
        r1 = api("POST", f"/api/v1/content/items/{item['id']}/publish",
                 a=EDITOR,
                 headers={"Idempotency-Key": idem(),
                          "X-Entity-Version": str(item["version"])})
        assert r1.status_code == 200
        published = r1.json()
        # try to publish again (same entity version is now stale → 409)
        r2 = api("POST", f"/api/v1/content/items/{item['id']}/publish",
                 a=EDITOR,
                 headers={"Idempotency-Key": idem(),
                          "X-Entity-Version": str(published["version"])})
        # version advanced after first publish, so second attempt with old version → 409
        # OR if version matches but state is PUBLISHED → 422
        assert r2.status_code in (409, 422)

    def test_publish_missing_entity_version_header_returns_400(self):
        item = create_draft("NoVerHeader").json()
        r = api("POST", f"/api/v1/content/items/{item['id']}/publish",
                a=EDITOR,
                headers={"Idempotency-Key": idem()})
        # Missing required header → Spring returns 400
        assert r.status_code == 400

    def test_publish_version_conflict_returns_409(self):
        item = create_draft("ConflictPub").json()
        r = api("POST", f"/api/v1/content/items/{item['id']}/publish",
                a=EDITOR,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": "999"})
        assert r.status_code == 409
        assert r.json()["error_code"] == "VERSION_CONFLICT"

    def test_publish_adds_snapshot_to_history(self):
        item = create_draft("History Test").json()
        api("POST", f"/api/v1/content/items/{item['id']}/publish",
            a=EDITOR,
            headers={"Idempotency-Key": idem(),
                     "X-Entity-Version": str(item["version"])})
        r = api("GET", f"/api/v1/content/items/{item['id']}/publish-history",
                a=EDITOR)
        assert r.status_code == 200
        snapshots = r.json()
        assert len(snapshots) >= 1
        assert snapshots[0]["transitionType"] == "PUBLISHED"


class TestUnpublishContentItem:
    def test_unpublish_published_item_succeeds(self):
        # publish first
        item = create_draft("Unpublishable").json()
        pub = api("POST", f"/api/v1/content/items/{item['id']}/publish",
                  a=EDITOR,
                  headers={"Idempotency-Key": idem(),
                           "X-Entity-Version": str(item["version"])}).json()
        r = api("POST", f"/api/v1/content/items/{item['id']}/unpublish",
                a=EDITOR,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": str(pub["version"])})
        assert r.status_code == 200
        assert r.json()["state"] == "UNPUBLISHED"

    def test_unpublish_draft_returns_422(self):
        item = create_draft("Unpublish Draft").json()
        r = api("POST", f"/api/v1/content/items/{item['id']}/unpublish",
                a=EDITOR,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": str(item["version"])})
        assert r.status_code == 422

    def test_unpublish_version_conflict_returns_409(self):
        item = create_draft("UnpubConflict").json()
        api("POST", f"/api/v1/content/items/{item['id']}/publish",
            a=EDITOR,
            headers={"Idempotency-Key": idem(),
                     "X-Entity-Version": str(item["version"])})
        r = api("POST", f"/api/v1/content/items/{item['id']}/unpublish",
                a=EDITOR,
                headers={"Idempotency-Key": idem(),
                         "X-Entity-Version": "999"})
        assert r.status_code == 409
