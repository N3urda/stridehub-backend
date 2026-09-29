#!/usr/bin/env python3
"""Call StrideHub's authenticated HTTP APIs using Python's standard library only.

Success JSON goes to stdout. Errors are JSON on stderr with a nonzero exit code.
No requests are retried, and redirects never receive the bearer credential.
"""

import argparse
import http.client
import json
import math
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


MAX_BODY_BYTES = 1024 * 1024
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30


class ClientError(Exception):
    def __init__(self, code, message, **details):
        super().__init__(message)
        self.payload = {"error": code, "message": message, **details}


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, _message):
        # argparse diagnostics can echo arbitrary arguments, including credentials.
        raise ClientError("invalid_arguments", "Use --help for the supported request arguments.")


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, _request, _fp, _code, _message, _headers, _url):
        return None


def reject_nonfinite(_value):
    raise ValueError("Non-finite JSON number")


def finite_json(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Non-finite JSON number")
    if isinstance(value, list):
        for item in value:
            finite_json(item)
    elif isinstance(value, dict):
        for item in value.values():
            finite_json(item)
    return value


def decode_json(raw):
    return finite_json(json.loads(raw.decode("utf-8"), parse_constant=reject_nonfinite))


def read_json_file(path, code, description):
    try:
        with path.open("rb") as source:
            raw = source.read(MAX_BODY_BYTES + 1)
        if len(raw) > MAX_BODY_BYTES:
            raise ValueError("Too large")
        value = decode_json(raw)
        if not isinstance(value, dict):
            raise ValueError("Not an object")
        return value
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise ClientError(code, description + " must be a readable JSON object no larger than 1 MiB.") from None


def validate_base_url(value):
    try:
        if not isinstance(value, str) or not value or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value):
            raise ValueError("Invalid URL")
        if "\\" in value or "%" in value:
            raise ValueError("Invalid URL")
        parts = urllib.parse.urlsplit(value)
        hostname = parts.hostname
        if (parts.scheme not in ("http", "https") or not hostname or parts.username is not None
                or parts.password is not None or parts.path not in ("", "/")
                or parts.query or parts.fragment or "?" in value or "#" in value):
            raise ValueError("Invalid URL")
        # Accessing port validates its syntax and range, without contacting a host.
        if parts.port is not None and parts.port == 0:
            raise ValueError("Invalid port")
        if parts.scheme == "http" and hostname.lower() not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("HTTP is only permitted on loopback")
        return urllib.parse.urlunsplit((parts.scheme, parts.netloc, "", "", ""))
    except (ValueError, TypeError):
        raise ClientError("invalid_config", "baseUrl must be an HTTPS origin, or HTTP on localhost, 127.0.0.1 or ::1; no credentials, path, query or fragment.") from None


def connection(config_argument):
    config_path = Path(config_argument).expanduser() if config_argument else Path.home() / ".config/stridehub/connection.json"
    config = {}
    if config_argument or config_path.exists():
        config = read_json_file(config_path, "invalid_config", "Connection configuration")
    base_url = validate_base_url(os.environ.get("STRIDEHUB_URL", config.get("baseUrl")))
    token = os.environ.get("DREEVE_API_KEY")
    if token is None:
        credentials = os.environ.get("STRIDEHUB_CREDENTIALS_FILE", config.get("credentialsFile"))
        if not isinstance(credentials, str) or not credentials:
            raise ClientError("invalid_config", "Set DREEVE_API_KEY or provide a credentialsFile containing apiKey.")
        credentials_path = Path(credentials).expanduser()
        if not credentials_path.is_absolute():
            credentials_path = config_path.parent / credentials_path
        token = read_json_file(credentials_path, "invalid_config", "Credentials file").get("apiKey")
    if (not isinstance(token, str) or not token or len(token) > 8192
            or any(ord(char) < 33 or ord(char) > 126 for char in token)):
        raise ClientError("invalid_config", "The API key must be a nonempty ASCII token without whitespace.")
    return base_url, token


def validate_path(value):
    try:
        if not value or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value):
            raise ValueError("Invalid path")
        parts = urllib.parse.urlsplit(value)
        if (parts.scheme or parts.netloc or parts.fragment or "#" in value or "\\" in value
                or not parts.path.startswith(("/api/v1/training/", "/api/v1/running/", "/api/v1/health/"))):
            raise ValueError("Invalid path")
        if re.search(r"%(?![0-9a-fA-F]{2})|%(?:2f|5c|25)", parts.path, re.IGNORECASE):
            raise ValueError("Unsafe encoding")
        decoded = urllib.parse.unquote(parts.path, errors="strict")
        if any(segment in (".", "..") for segment in decoded.split("/")):
            raise ValueError("Traversal")
        if any(ord(char) < 32 or ord(char) == 127 for char in decoded):
            raise ValueError("Control character")
        # Encode any Unicode path/query text while preserving already encoded bytes.
        return urllib.parse.quote(value, safe="/%?:=&+;,@!$'()*-._~")
    except (ValueError, UnicodeError):
        raise ClientError("invalid_path", "Use a rooted /api/v1/training/, /api/v1/running/ or /api/v1/health/ path without traversal, encoded separators or fragments.") from None


def request_body(method, body_file):
    if method == "GET" and body_file is not None:
        raise ClientError("invalid_body", "GET requests cannot contain a body.")
    if method in ("POST", "PUT") and body_file is None:
        raise ClientError("invalid_body", "POST and PUT require --body-file containing a JSON object; use - for stdin.")
    if body_file is None:
        return None
    try:
        if body_file == "-":
            raw = sys.stdin.buffer.read(MAX_BODY_BYTES + 1)
        else:
            with Path(body_file).expanduser().open("rb") as source:
                raw = source.read(MAX_BODY_BYTES + 1)
        if len(raw) > MAX_BODY_BYTES:
            raise ValueError("Too large")
        value = decode_json(raw)
        if not isinstance(value, dict):
            raise ValueError("Not an object")
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
        if len(encoded) > MAX_BODY_BYTES:
            raise ValueError("Too large")
        return encoded
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise ClientError("invalid_body", "The request body must be a readable UTF-8 JSON object, at most 1 MiB, with finite numbers.") from None


def redact(value, token):
    if isinstance(value, str):
        return value.replace(token, "[REDACTED]") if token else value
    if isinstance(value, list):
        return [redact(item, token) for item in value]
    if isinstance(value, dict):
        return {redact(key, token): redact(item, token) for key, item in value.items()}
    return value


def perform_request(base_url, token, method, path, body):
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json", "User-Agent": "stridehub-coach/1.0"}
    if body is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(base_url + path, data=body, headers=headers, method=method)
    # Ignore environment proxy configuration so a bearer credential is only sent
    # to the explicitly configured origin, including for loopback development.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
    try:
        try:
            response = opener.open(request, timeout=REQUEST_TIMEOUT_SECONDS)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            if 300 <= status < 400:
                raise ClientError("redirect_refused", "Redirect refused; configure the final API origin explicitly.", status=status)
            if status == 204:
                return {"status": status}
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ClientError("invalid_response", "Response exceeded the 2 MiB limit; narrow the query or paginate.", status=status)
            try:
                payload = decode_json(raw)
            except (ValueError, UnicodeError, RecursionError):
                raise ClientError("invalid_response", "The service did not return valid finite UTF-8 JSON; response text was omitted.", status=status) from None
            if not 200 <= status < 300:
                raise ClientError("http_error", "The API returned an error; no automatic retry was performed.", status=status, response=payload)
            return payload
    except ClientError as error:
        status = error.payload["status"]
        # A write may commit before a proxy or response encoder fails, or before
        # a server sends a redirect. Only a 4xx response is a definite rejection.
        uncertain = method != "GET" and (200 <= status < 400 or status >= 500)
        error.payload["outcomeUnknown"] = uncertain
        if uncertain:
            error.payload["message"] += " The write outcome is unknown: read back the resource with GET before deciding whether to retry."
        raise
    except (urllib.error.URLError, OSError, http.client.HTTPException, UnicodeError):
        writing = method != "GET"
        message = "The request could not be completed; no automatic retry was performed."
        if writing:
            message += " The write outcome is unknown: read back the resource with GET before deciding whether to retry."
        raise ClientError("network_error", message, outcomeUnknown=writing) from None


def main(argv=None):
    token = os.environ.get("DREEVE_API_KEY", "")
    try:
        parser = JsonArgumentParser(description=__doc__)
        parser.add_argument("--config", metavar="PATH", help="Connection JSON; default ~/.config/stridehub/connection.json")
        commands = parser.add_subparsers(dest="command", required=True)
        request = commands.add_parser("request", help="Make one authenticated request, without retries")
        request.add_argument("method", choices=("GET", "POST", "PUT", "DELETE"))
        request.add_argument("path", help="Rooted /api/v1/training/, running/ or health/ path; query strings are allowed")
        request.add_argument("--body-file", metavar="FILE|-", help="JSON object file, or - for stdin")
        args = parser.parse_args(argv)
        path = validate_path(args.path)
        body = request_body(args.method, args.body_file)
        base_url, token = connection(args.config)
        payload = perform_request(base_url, token, args.method, path, body)
        print(json.dumps(redact(payload, token), ensure_ascii=False, allow_nan=False))
        return 0
    except ClientError as error:
        print(json.dumps(redact(error.payload, token), ensure_ascii=False, allow_nan=False), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print(json.dumps({"error": "interrupted", "message": "Interrupted; read back any requested write before retrying."}), file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
