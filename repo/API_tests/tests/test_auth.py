"""
Authentication tests:
  - login success returns user object
  - wrong password → 401
  - missing body fields → 422
  - /me without credentials → 401
  - /me with valid credentials → 200
"""
import pytest
from conftest import api, ADMIN, EDITOR, auth, BASE_URL


class TestLogin:
    def test_login_success_returns_user(self):
        r = api("POST", "/api/v1/auth/login",
                json={"username": "admin", "password": "admin123"})
        assert r.status_code == 200
        body = r.json()
        assert body["username"] == "admin"
        assert body["role"] == "SYSTEM_ADMIN"
        assert "id" in body

    def test_login_wrong_password_returns_401(self):
        r = api("POST", "/api/v1/auth/login",
                json={"username": "admin", "password": "wrongpass"})
        assert r.status_code == 401
        assert r.json()["error_code"] == "UNAUTHORIZED"

    def test_login_unknown_user_returns_401(self):
        r = api("POST", "/api/v1/auth/login",
                json={"username": "nobody", "password": "x"})
        assert r.status_code == 401

    def test_login_missing_username_returns_422(self):
        r = api("POST", "/api/v1/auth/login", json={"password": "admin123"})
        assert r.status_code == 422
        assert r.json()["error_code"] == "VALIDATION_FAILED"

    def test_login_missing_password_returns_422(self):
        r = api("POST", "/api/v1/auth/login", json={"username": "admin"})
        assert r.status_code == 422

    def test_login_empty_body_returns_422(self):
        r = api("POST", "/api/v1/auth/login", json={})
        assert r.status_code == 422

    def test_all_seed_roles_can_login(self):
        for username in ["admin", "editor", "clerk", "driver1",
                         "moderator", "dispatcher", "auditor"]:
            r = api("POST", "/api/v1/auth/login",
                    json={"username": username, "password": "admin123"})
            assert r.status_code == 200, f"{username} login failed: {r.text}"


class TestMe:
    def test_me_without_auth_returns_401(self):
        r = api("GET", "/api/v1/auth/me")
        assert r.status_code == 401

    def test_me_with_valid_credentials_returns_user(self):
        r = api("GET", "/api/v1/auth/me", a=ADMIN)
        assert r.status_code == 200
        assert r.json()["username"] == "admin"

    def test_me_with_wrong_credentials_returns_401(self):
        r = api("GET", "/api/v1/auth/me", a=auth("admin", "bad"))
        assert r.status_code == 401

    def test_me_returns_correct_role_for_editor(self):
        r = api("GET", "/api/v1/auth/me", a=EDITOR)
        assert r.status_code == 200
        assert r.json()["role"] == "CONTENT_EDITOR"
