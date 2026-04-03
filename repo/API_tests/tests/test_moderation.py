"""
Moderation tests:
  - add sensitive word
  - list sensitive words (paginated)
  - comment with 0 hits → APPROVED
  - comment with 1 hit  → FLAGGED
  - comment with 2 hits → HOLD
  - moderate comment (approve, reject)
  - moderate with version conflict → 409
  - duplicate sensitive word → 409
"""
import pytest
from conftest import (api, ADMIN, EDITOR, MODERATOR, DISPATCHER, CLERK, idem,
                      CONTENT_DRAFT_ID, ORG_ID)


def ensure_content_item():
    """Return id of a content item that can accept comments."""
    r = api("POST", "/api/v1/content/items",
            a=EDITOR,
            json={"type": "NEWS", "title": "Moderation Test Article"},
            headers={"Idempotency-Key": idem()})
    assert r.status_code == 201
    return r.json()["id"]


def add_word(word, replacement=None):
    payload = {"word": word}
    if replacement:
        payload["replacement"] = replacement
    return api("POST", "/api/v1/moderation/sensitive-words",
               a=MODERATOR, json=payload)


def post_comment(content_id, text):
    return api("POST", f"/api/v1/content/items/{content_id}/comments",
               a=ADMIN,
               json={"contentText": text},
               headers={"Idempotency-Key": idem()})


class TestSensitiveWords:
    def test_add_sensitive_word_returns_201(self):
        r = add_word(f"badword-{idem()[:8]}")
        assert r.status_code == 201
        assert "word" in r.json()

    def test_add_duplicate_word_returns_409(self):
        word = f"dup-{idem()[:8]}"
        r1 = add_word(word)
        assert r1.status_code == 201
        r2 = add_word(word)
        assert r2.status_code == 409

    def test_add_word_missing_word_field_returns_422(self):
        r = api("POST", "/api/v1/moderation/sensitive-words",
                a=MODERATOR, json={"replacement": "xxx"})
        assert r.status_code == 422

    def test_list_sensitive_words_returns_page(self):
        r = api("GET", "/api/v1/moderation/sensitive-words", a=MODERATOR)
        assert r.status_code == 200
        body = r.json()
        assert "data" in body and "total" in body
        assert isinstance(body["data"], list)


class TestCommentModeration:
    @pytest.fixture(scope="class")
    def content_id(self):
        return ensure_content_item()

    @pytest.fixture(scope="class", autouse=True)
    def setup_words(self, content_id):
        """Seed two distinct sensitive words for this test class."""
        self._word_a = f"sw-alpha-{idem()[:6]}"
        self._word_b = f"sw-beta-{idem()[:6]}"
        add_word(self._word_a)
        add_word(self._word_b)

    def test_comment_no_hits_is_approved(self, content_id):
        r = post_comment(content_id, "This is a perfectly fine comment.")
        assert r.status_code == 201
        assert r.json()["moderationState"] == "APPROVED"
        assert r.json()["filterHitCount"] == 0

    def test_comment_one_hit_is_flagged(self, content_id):
        text = f"This comment mentions {self._word_a} once."
        r = post_comment(content_id, text)
        assert r.status_code == 201
        assert r.json()["moderationState"] == "FLAGGED"
        assert r.json()["filterHitCount"] == 1

    def test_comment_two_hits_is_hold(self, content_id):
        text = f"This has {self._word_a} and also {self._word_b} in it."
        r = post_comment(content_id, text)
        assert r.status_code == 201
        assert r.json()["moderationState"] == "HOLD"
        assert r.json()["filterHitCount"] == 2

    def test_moderate_comment_to_approved(self, content_id):
        text = f"Flagged text: {self._word_a}"
        comment = post_comment(content_id, text).json()
        assert comment["moderationState"] == "FLAGGED"

        r = api("POST", f"/api/v1/comments/{comment['id']}/moderate",
                a=MODERATOR,
                json={"moderationState": "APPROVED",
                      "reason": "Reviewed and OK"})
        assert r.status_code == 200
        assert r.json()["moderationState"] == "APPROVED"

    def test_moderate_comment_to_rejected(self, content_id):
        text = f"Held text: {self._word_a} and {self._word_b}"
        comment = post_comment(content_id, text).json()
        assert comment["moderationState"] == "HOLD"

        r = api("POST", f"/api/v1/comments/{comment['id']}/moderate",
                a=MODERATOR,
                json={"moderationState": "REJECTED",
                      "reason": "Policy violation"})
        assert r.status_code == 200
        assert r.json()["moderationState"] == "REJECTED"

    def test_moderate_comment_version_conflict_returns_409(self, content_id):
        comment = post_comment(content_id, "Normal comment.").json()
        r = api("POST", f"/api/v1/comments/{comment['id']}/moderate",
                a=MODERATOR,
                json={"moderationState": "APPROVED",
                      "reason": "OK",
                      "entityVersion": 9999})
        assert r.status_code == 409
        assert r.json()["error_code"] == "VERSION_CONFLICT"

    def test_moderate_nonexistent_comment_returns_404(self):
        r = api("POST",
                "/api/v1/comments/00000000-0000-0000-0000-000000009999/moderate",
                a=MODERATOR,
                json={"moderationState": "APPROVED", "reason": "OK"})
        assert r.status_code == 404
