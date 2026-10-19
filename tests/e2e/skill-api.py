#!/usr/bin/env python3
"""Real HTTP smoke test for the Skill helper, against an EMPTY disposable instance.

Start an isolated StrideHub runtime with its own copied database at localhost:8082,
then explicitly set STRIDEHUB_TEST_URL=http://localhost:8082 before running this file.
The credentials file defaults to ignored var/runtime/credentials.json; override it
with STRIDEHUB_TEST_CREDENTIALS_FILE if needed. The test leaves clearly synthetic
health fixtures only in that disposable database and deletes its training sessions.
It never prints credentials or report bodies and never contacts the personal :8081.
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "skills/stridehub-coach/scripts/stridehub_api.py"
BASE = "http://localhost:8082"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    require(os.environ.get("STRIDEHUB_TEST_URL") == BASE,
            "Refusing to run: explicitly set STRIDEHUB_TEST_URL=http://localhost:8082 for a disposable instance.")
    credentials = Path(os.environ.get("STRIDEHUB_TEST_CREDENTIALS_FILE", str(ROOT / "var/runtime/credentials.json"))).resolve()
    require(credentials.is_file() and HELPER.is_file(), "Required local helper or credentials file is missing.")
    environment = os.environ.copy()
    for key in ("STRIDEHUB_URL", "STRIDEHUB_CREDENTIALS_FILE", "DREEVE_API_KEY"):
        environment.pop(key, None)
    passed = []

    def report(name):
        passed.append(name)
        print("PASS " + name, flush=True)

    with tempfile.TemporaryDirectory(prefix="stridehub-skill-e2e-") as directory:
        config = Path(directory) / "connection.json"
        config.write_text(json.dumps({"baseUrl": BASE, "credentialsFile": str(credentials)}), encoding="utf-8")
        config.chmod(0o600)

        def request(method, path, body=None, failure_status=None):
            command = [sys.executable, str(HELPER), "--config", str(config), "request", method, path]
            if body is not None:
                command += ["--body-file", "-"]
            process = subprocess.run(command, input=None if body is None else json.dumps(body, ensure_ascii=False),
                                     text=True, encoding="utf-8", capture_output=True, env=environment, timeout=40, check=False)
            expected_code = 0 if failure_status is None else 1
            require(process.returncode == expected_code, "Unexpected helper exit code for " + method + " " + path)
            output = process.stdout if failure_status is None else process.stderr
            try:
                data = json.loads(output)
            except (ValueError, TypeError):
                raise RuntimeError("Helper returned invalid JSON; output intentionally omitted.") from None
            if failure_status is None:
                require(not process.stderr, "Successful helper emitted unexpected stderr.")
            else:
                require(not process.stdout and data.get("status") == failure_status, "Unexpected HTTP error result.")
                require(data.get("outcomeUnknown") is False, "A rejected write must not be marked as uncertain.")
            return data

        # These guards run before any mutation. Never run on a populated personal database.
        empty_health = request("GET", "/api/v1/health/context")
        require(empty_health.get("version") == 0 and empty_health.get("reports") == []
                and empty_health.get("constraints") == [] and empty_health.get("lifestylePreferences") == [],
                "Refusing mutation: disposable health context is not empty.")
        require(request("GET", "/api/v1/training/sessions").get("items") == [],
                "Refusing mutation: disposable training sessions are not empty.")
        report("empty disposable health and session guards")

        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            opener.open(BASE + "/api/v1/health/context", timeout=10)
            raise RuntimeError("Unauthenticated health context unexpectedly succeeded.")
        except urllib.error.HTTPError as error:
            with error:
                require(error.code == 401 and "no-store" in error.headers.get("Cache-Control", ""),
                        "Health authentication failure must be 401 and non-cacheable.")
        report("real Bearer rejection with no-store")

        for namespace in ("training", "running", "health"):
            schema = request("GET", "/api/v1/" + namespace + "/openapi.json")
            require(schema.get("openapi") == "3.1.0" and schema.get("paths"), "OpenAPI response is incomplete.")
            report("helper reads authenticated " + namespace + " OpenAPI")

        first_report = {
            "id": "DEMO-SKILL-report-1", "title": "DEMO synthetic report one", "reportDate": "2026-09-28",
            "sourceLabel": "DEMO fixture, no real person or examination",
            "findings": [{"name": "DEMO item", "valueText": "DEMO raw result", "page": 1}],
            "clinicianAdvice": ["DEMO transcription for API testing only"],
            "aiInterpretation": "DEMO interpretation stored separately", "needsReview": True,
        }
        constraint = {"id": "DEMO-SKILL-limit-1", "description": "DEMO synthetic constraint",
                      "sourceType": "user", "sourceReportId": first_report["id"], "status": "active"}
        first = request("PUT", "/api/v1/health/context", {"version": 0, "reports": [first_report],
                        "constraints": [constraint], "lifestylePreferences": ["DEMO preference"],
                        "notes": "DEMO disposable fixture; not a health recommendation"})
        read_first = request("GET", "/api/v1/health/context")
        require(first == read_first and first["version"] == 1, "Initial health write did not persist.")
        require(first["reports"][0]["findings"][0]["referenceRangeText"] == ""
                and first["reports"][0]["findings"][0]["flag"] == "unknown",
                "Missing report reference ranges must remain unspecified.")
        report("health write and read-back preserve separate source and AI fields")

        second_report = {**first_report, "id": "DEMO-SKILL-report-2", "title": "DEMO synthetic report two"}
        second = request("PUT", "/api/v1/health/context", {"version": first["version"],
                         "reports": [*first["reports"], second_report]})
        read_second = request("GET", "/api/v1/health/context")
        require(second == read_second and second["version"] == 2 and len(second["reports"]) == 2,
                "Appended report was not persisted.")
        require(second["reports"][0] == first["reports"][0]
                and all(second[field] == first[field] for field in ("constraints", "lifestylePreferences", "notes")),
                "Appending a report overwrote an earlier report or omitted context fields.")
        report("report append retains earlier report, constraints and lifestyle preferences")

        rejected = request("PUT", "/api/v1/health/context", {"version": first["version"], "notes": "DEMO stale overwrite"}, failure_status=409)
        require(rejected.get("response", {}).get("error") == "conflict", "Health stale version did not return conflict.")
        require(request("GET", "/api/v1/health/context") == second, "Stale health write changed persisted context.")
        report("stale health version returns 409 without overwriting data")

        sessions = [
            {"id": "DEMO-SKILL-session-1", "version": 0, "title": "DEMO synthetic easy run",
             "startAt": "2026-10-01T06:30:00+08:00", "durationMinutes": 30, "type": "easy"},
            {"id": "DEMO-SKILL-session-2", "version": 0, "title": "DEMO synthetic rest",
             "startAt": "2026-10-02T06:30:00+08:00", "durationMinutes": 1, "type": "rest"},
        ]
        created = request("PUT", "/api/v1/training/sessions/batch", {"sessions": sessions})["items"]
        require(len(created) == 2 and all(item["version"] == 1 for item in created), "Batch did not create both sessions.")
        for item in created:
            require(request("GET", "/api/v1/training/sessions/" + item["id"]) == item, "Created session did not persist.")
        report("helper creates and reads two stable-ID sessions through atomic batch")

        request("PUT", "/api/v1/training/sessions/batch", {"sessions": [
            {"id": created[0]["id"], "version": 1, "notes": "DEMO must roll back"},
            {"id": created[1]["id"], "version": 0, "notes": "DEMO stale second item"},
        ]}, failure_status=409)
        for item in created:
            require(request("GET", "/api/v1/training/sessions/" + item["id"]) == item,
                    "Conflicting batch failed to roll back an earlier session mutation.")
        report("batch conflict rolls back the earlier valid mutation")

        for item in created:
            deleted = request("DELETE", "/api/v1/training/sessions/" + item["id"] + "?version=" + str(item["version"]))
            require(deleted == {"status": 204}, "Helper did not handle the empty 204 response.")
        require(request("GET", "/api/v1/training/sessions")["items"] == [], "Synthetic sessions were not deleted.")
        report("helper handles DELETE 204 and confirms empty sessions")

    print(json.dumps({"passed": len(passed), "result": "PASS", "transport": "real HTTP via Skill subprocess",
                      "fixtures": "synthetic health only, disposable database", "personalInstanceTouched": False}))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        # Exceptions above contain only static diagnostics and public API paths.
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
