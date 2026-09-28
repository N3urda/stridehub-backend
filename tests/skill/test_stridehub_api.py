"""Contract tests using a disposable local HTTP server, never a StrideHub database."""

import contextlib
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "skills/stridehub-coach/scripts/stridehub_api.py"
TOKEN = "fixture-sensitive-token-93"


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.respond()

    do_POST = do_PUT = do_DELETE = do_GET

    def respond(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.server.requests.append((self.command, self.path, dict(self.headers), body))
        route = self.path.split("?", 1)[0]
        status, response, content_type = 200, {"ok": True}, "application/json"
        if route.endswith("/echo"):
            response = {"payload": json.loads(body), "path": self.path}
        elif route.endswith("/conflict"):
            status, response = 409, {"error": "version_conflict", "version": 2}
        elif route.endswith("/unauthorized"):
            status, response = 401, {"error": "unauthorized"}
        elif route.endswith("/server-error"):
            status, response = 500, {"error": "commit_response_failed"}
        elif route.endswith("/reflect"):
            response = {"detail": "Bearer " + TOKEN, "nested": [TOKEN], TOKEN: TOKEN}
        elif route.endswith("/reflect-error"):
            status, response = 403, {"detail": "Bearer " + TOKEN}
        elif route.endswith("/disconnect"):
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            return
        elif route.endswith("/redirect"):
            self.send_response(302)
            self.send_header("Location", self.server.url + "/api/v1/training/forwarded")
            self.end_headers()
            return
        elif route.endswith("/empty"):
            self.send_response(204)
            self.end_headers()
            return
        elif route.endswith("/html"):
            status, response, content_type = 502, "<html>" + TOKEN + "</html>", "text/html"
        elif route.endswith("/large"):
            response = {"data": "x" * (2 * 1024 * 1024)}
        elif route.endswith("/invalid-json"):
            response = '{"value": NaN}'
        raw = response.encode() if isinstance(response, str) else json.dumps(response).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        with contextlib.suppress(BrokenPipeError, ConnectionResetError):
            self.wfile.write(raw)


class StrideHubApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        cls.server.url = "http://127.0.0.1:" + str(cls.server.server_port)
        cls.server.requests = []
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.server.requests.clear()
        self.env = {key: value for key, value in os.environ.items()
                    if key not in ("STRIDEHUB_URL", "DREEVE_API_KEY", "STRIDEHUB_CREDENTIALS_FILE")}
        self.env.update(HOME=str(self.root), STRIDEHUB_URL=self.server.url, DREEVE_API_KEY=TOKEN)

    def cli(self, *args, stdin=None):
        return subprocess.run([sys.executable, str(SCRIPT), *args], env=self.env,
                              input=stdin, text=True, capture_output=True, timeout=10)

    def error(self, result, code=None):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        data = json.loads(result.stderr)
        self.assertEqual(result.stdout, "")
        if code:
            self.assertEqual(data["error"], code)
        return data

    def write_json(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        return path

    def test_sends_real_bearer_and_nested_utf8_json(self):
        payload = {"title": "上海马拉松", "steps": [{"pace": 320, "notes": None}]}
        body = self.write_json("plan.json", payload)
        result = self.cli("request", "POST", "/api/v1/training/echo?date=2026-10-01", "--body-file", str(body))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["payload"], payload)
        method, path, headers, raw = self.server.requests[0]
        self.assertEqual(method, "POST")
        self.assertEqual(headers["Authorization"], "Bearer " + TOKEN)
        self.assertIn("application/json", headers["Content-Type"])
        self.assertIn("上海".encode(), raw)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)

    def test_accepts_stdin_body(self):
        result = self.cli("request", "PUT", "/api/v1/training/echo", "--body-file", "-", stdin='{"version": 1}')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["payload"], {"version": 1})

    def test_accepts_running_and_health_read_endpoints(self):
        for path in ("/api/v1/running/overview?from=all&page=2", "/api/v1/health/context"):
            result = self.cli("request", "GET", path)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_http_failures_are_not_retried(self):
        body = self.write_json("plan.json", {"version": 1})
        for route, status in (("conflict", 409), ("unauthorized", 401)):
            self.server.requests.clear()
            result = self.cli("request", "POST", "/api/v1/training/" + route, "--body-file", str(body))
            data = self.error(result, "http_error")
            self.assertEqual(data["status"], status)
            self.assertIs(data.get("outcomeUnknown"), False)
            self.assertEqual(len(self.server.requests), 1)
            self.assertEqual(data["response"]["error"], "version_conflict" if status == 409 else "unauthorized")

    def test_redirect_is_not_followed_or_forwarded_credentials(self):
        result = self.cli("request", "GET", "/api/v1/training/redirect")
        self.error(result, "redirect_refused")
        self.assertEqual(len(self.server.requests), 1)

    def test_204_is_success_with_json_output(self):
        result = self.cli("request", "DELETE", "/api/v1/training/empty")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"status": 204})

    def test_response_redacts_reflected_secrets_recursively_including_keys(self):
        result = self.cli("request", "GET", "/api/v1/training/reflect")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        self.assertIn("[REDACTED]", result.stdout)

    def test_http_error_redacts_reflected_credential(self):
        result = self.cli("request", "GET", "/api/v1/training/reflect-error")
        data = self.error(result, "http_error")
        self.assertEqual(data["response"]["detail"], "Bearer [REDACTED]")

    def test_non_json_error_does_not_print_html(self):
        result = self.cli("request", "GET", "/api/v1/training/html")
        data = self.error(result, "invalid_response")
        self.assertEqual(data["status"], 502)
        self.assertNotIn("<html>", result.stderr)

    def test_response_size_limit_and_nonfinite_json(self):
        for route in ("large", "invalid-json"):
            with self.subTest(route=route):
                self.error(self.cli("request", "GET", "/api/v1/training/" + route), "invalid_response")

    def test_rejects_invalid_paths_before_any_request(self):
        for path in ("https://evil.example/api/v1/training/x", "//evil.example/api/v1/training/x",
                     "/admin/training", "/api/v1/training/../running/x", "/api/v1/training/%2e%2e/x",
                     "/api/v1/training/%252e%252e/x", "/api/v1/training/x%2fy", "/api/v1/training/x%5cy",
                     "/api/v1/training/x\\y", "/api/v1/training/x#fragment", "/api/v1/training/x\n",
                     "/api/v1/trainingx/", "/api/v1/training/./sessions"):
            with self.subTest(path=path):
                self.error(self.cli("request", "GET", path), "invalid_path")
        self.assertEqual(self.server.requests, [])

    def test_rejects_unsafe_base_urls(self):
        for base in ("http://example.com", "http://127.0.0.1.evil.test", "http://localhost.evil.test",
                     "http://user:pass@localhost", "https://example.com/subpath", "https://example.com?q=1",
                     "https://example.com#hash", "file:///tmp/secret", "http://localhost\\@evil.test",
                     "http://localhost:bad", "https://example.com\n"):
            with self.subTest(base=base):
                self.env["STRIDEHUB_URL"] = base
                self.error(self.cli("request", "GET", "/api/v1/health/context"), "invalid_config")
        self.assertEqual(self.server.requests, [])

    def test_del_character_in_base_url_is_rejected_as_configuration_error(self):
        self.env["STRIDEHUB_URL"] = "https://127.0.0.1\x7f"
        self.error(self.cli("request", "GET", "/api/v1/health/context"), "invalid_config")

    def test_missing_body_file_and_invalid_utf8_are_safe_errors(self):
        self.error(self.cli("request", "POST", "/api/v1/training/echo", "--body-file", str(self.root / "missing.json")), "invalid_body")
        body = self.root / "invalid.json"
        body.write_bytes(b'{"value":"\xff"}')
        self.error(self.cli("request", "POST", "/api/v1/training/echo", "--body-file", str(body)), "invalid_body")

    def test_rejects_body_on_get_and_requires_object_on_writes(self):
        for body in ('[]', 'null', '{"value":NaN}', '{"value":Infinity}', '{"value":1e999}', '{invalid', ''):
            with self.subTest(body=body):
                self.error(self.cli("request", "POST", "/api/v1/training/echo", "--body-file", "-", stdin=body), "invalid_body")
        self.error(self.cli("request", "GET", "/api/v1/training/echo", "--body-file", "-", stdin="{}"), "invalid_body")
        for method in ("POST", "PUT"):
            self.error(self.cli("request", method, "/api/v1/training/echo"), "invalid_body")
        self.assertEqual(self.server.requests, [])

    def test_body_size_limit(self):
        self.error(self.cli("request", "POST", "/api/v1/training/echo", "--body-file", "-",
                            stdin=json.dumps({"value": "x" * (1024 * 1024)})), "invalid_body")
        self.assertEqual(self.server.requests, [])

    def test_default_config_resolves_relative_credentials(self):
        self.env.pop("STRIDEHUB_URL")
        self.env.pop("DREEVE_API_KEY")
        self.write_json(".config/stridehub/secret.json", {"apiKey": TOKEN, "password": "unused"})
        self.write_json(".config/stridehub/connection.json", {"baseUrl": self.server.url, "credentialsFile": "secret.json"})
        result = self.cli("request", "GET", "/api/v1/health/context")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.server.requests[0][2]["Authorization"], "Bearer " + TOKEN)

    def test_environment_url_key_and_credentials_override_config(self):
        config = self.write_json("settings/connection.json", {"baseUrl": "https://wrong.invalid", "credentialsFile": "missing.json"})
        result = self.cli("--config", str(config), "request", "GET", "/api/v1/health/context")
        self.assertEqual(result.returncode, 0, result.stderr)
        credentials = self.write_json("env-secret.json", {"apiKey": TOKEN})
        self.env.pop("DREEVE_API_KEY")
        self.env["STRIDEHUB_CREDENTIALS_FILE"] = str(credentials)
        result = self.cli("--config", str(config), "request", "GET", "/api/v1/health/context")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.server.requests[-1][2]["Authorization"], "Bearer " + TOKEN)

    def test_missing_or_invalid_configuration_returns_json_without_secrets(self):
        self.env.pop("DREEVE_API_KEY")
        self.error(self.cli("request", "GET", "/api/v1/health/context"), "invalid_config")
        config = self.write_json("bad.json", [TOKEN])
        self.error(self.cli("--config", str(config), "request", "GET", "/api/v1/health/context"), "invalid_config")

    def test_connection_failure_is_not_assumed_successful_write(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.env["STRIDEHUB_URL"] = "http://127.0.0.1:" + str(sock.getsockname()[1])
        result = self.cli("request", "POST", "/api/v1/training/sessions", "--body-file", "-", stdin="{}")
        data = self.error(result, "network_error")
        self.assertIs(data.get("outcomeUnknown"), True)
        self.assertIn("read", data["message"].lower())

    def test_lost_response_after_write_is_not_retried_and_requires_readback(self):
        result = self.cli("request", "POST", "/api/v1/training/disconnect", "--body-file", "-", stdin="{}")
        data = self.error(result, "network_error")
        self.assertEqual(len(self.server.requests), 1)
        self.assertIs(data.get("outcomeUnknown"), True)
        self.assertIn("read back", data["message"])

    def test_server_error_after_write_requires_readback_without_retry(self):
        result = self.cli("request", "POST", "/api/v1/training/server-error", "--body-file", "-", stdin="{}")
        data = self.error(result, "http_error")
        self.assertEqual(data["status"], 500)
        self.assertEqual(len(self.server.requests), 1)
        self.assertIs(data.get("outcomeUnknown"), True)
        self.assertIn("read back", data["message"])
        self.assertNotIn("rejected", data["message"])

    def test_invalid_or_oversized_response_after_write_requires_readback(self):
        for route in ("invalid-json", "large", "html"):
            with self.subTest(route=route):
                self.server.requests.clear()
                result = self.cli("request", "PUT", "/api/v1/training/" + route, "--body-file", "-", stdin="{}")
                data = self.error(result, "invalid_response")
                self.assertEqual(len(self.server.requests), 1)
                self.assertIs(data.get("outcomeUnknown"), True)
                self.assertIn("read back", data["message"])

    def test_redirect_after_write_requires_readback_and_is_not_followed(self):
        result = self.cli("request", "POST", "/api/v1/training/redirect", "--body-file", "-", stdin="{}")
        data = self.error(result, "redirect_refused")
        self.assertEqual(len(self.server.requests), 1)
        self.assertIs(data.get("outcomeUnknown"), True)
        self.assertIn("read back", data["message"])

    def test_invalid_arguments_are_json_errors(self):
        self.error(self.cli("request", "PATCH", "/api/v1/training/sessions"), "invalid_arguments")


if __name__ == "__main__":
    unittest.main()
