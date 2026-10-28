#!/usr/bin/env python3
"""Verify the native API through the actual public HTTPS entry, without redirects.

The default checks never send a password or create records. Optional credentials
create a short-lived mobile session, read the native workspace, then revoke it.
Passwords, tokens and response bodies are never written to the report.
"""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def verify(base, credentials=None):
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("A complete HTTPS service address is required")
    base = base.rstrip("/")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    checks = []

    def request(path, expected, method="GET", body=None, token=None):
        headers = {"Accept": "application/json", "Connection": "close"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            response = opener.open(req, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content_type = response.headers.get_content_type()
            raw = response.read(16 * 1024 * 1024 + 1)
            check = {"path": path, "method": method, "status": response.status,
                     "expectedStatus": expected, "contentType": content_type}
            checks.append(check)
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, received {response.status} ({content_type})")
            if expected == 204:
                assert not raw, "Revocation must return an empty response"
                return {}
            if content_type != "application/json":
                raise AssertionError(f"{path}: native API returned {content_type}; check the public proxy route")
            if len(raw) > 16 * 1024 * 1024:
                raise AssertionError("Response exceeded the native client size limit")
            value = json.loads(raw)
            assert isinstance(value, dict), "Native API must return a JSON object"
            check["passed"] = True
            return value

    token = None
    failure = None
    try:
        request("/mobile/v1/sessions", 422, "POST", {})
        request("/mobile/v1/session", 401)
        request("/mobile/v1/collection/devices", 401, "POST", {})
        request("/api/v1/mobile/workouts", 401)
        request("/device/v1/weight", 401)
        if credentials:
            session = request("/mobile/v1/sessions", 201, "POST", {
                "username": credentials["username"], "password": credentials["password"],
                "deviceName": "Public HTTPS acceptance"})
            token = session["token"]
            assert session["apiVersion"] == 1 and "mobile-workflows" in session["capabilities"]
            request("/mobile/v1/session", 200, token=token)
            for path in ["/api/v1/health/workspace", "/api/v1/mobile/workouts",
                         "/api/v1/mobile/activity-options", "/api/v1/mobile/imports",
                         "/api/v1/mobile/sync", "/api/v1/running/overview"]:
                request(path, 200, token=token)
    except (AssertionError, ValueError, OSError) as error:
        failure = str(error) if isinstance(error, AssertionError) else type(error).__name__
    finally:
        if token:
            try:
                request("/mobile/v1/session", 204, "DELETE", token=token)
                request("/mobile/v1/session", 401, token=token)
            except (AssertionError, ValueError, OSError) as error:
                failure = failure or "Session revocation verification failed: " + type(error).__name__
    return {"base": base, "passed": failure is None, "passwordLoginTested": credentials is not None,
            "tlsCertificateVerification": True, "redirectsAllowed": False,
            "checks": checks, "failure": failure}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--credentials", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    account = json.loads(args.credentials.read_text()) if args.credentials else None
    report = verify(args.base, account)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False))
    raise SystemExit(0 if report["passed"] else 1)
