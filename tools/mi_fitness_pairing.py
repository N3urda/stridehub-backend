"""Private, single-account QR pairing for the pinned Mi Fitness connector.

The browser never receives Xiaomi URLs, tokens, account IDs or connector keys.
Only the PHP backend may access this control API using its private config file.
"""
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import time
from types import SimpleNamespace
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response

PNG = b'\x89PNG\r\n\x1a\n'
ACCOUNT_TABLES = ('daily_activity', 'sleep_sessions', 'workouts', 'body_measurements',
                  'heart_rate_samples', 'spo2_samples', 'stress_samples',
                  'abnormal_heart_beat_events', 'stridehub_sleep_details')


def write_private(path, data):
    """Replace a secret atomically; also fix permissions of an older loose file."""
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(data, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def read_private(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 16384 or path.stat().st_mode & 0o077:
        raise ValueError('Private configuration unavailable')
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise ValueError('Invalid private configuration')
    return data


def valid_secret(value):
    return isinstance(value, str) and 0 < len(value) <= 4096 and not re.search(r'[\x00-\x20\x7f]', value)


def prepare_control(config_dir, base_url):
    parsed = urlsplit(base_url)
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ('', '/')
            or (parsed.scheme == 'http' and parsed.hostname not in ('127.0.0.1', 'localhost', '::1', 'mi-fitness'))):
        raise ValueError('Unsupported connector address')
    config_dir = Path(config_dir)
    config_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = config_dir / 'pairing-control.json'
    control = read_private(path) if path.exists() else {'apiKey': secrets.token_urlsafe(48)}
    if not valid_secret(control.get('apiKey')) or len(control['apiKey']) < 32:
        raise ValueError('Invalid control key')
    control = {'baseUrl': base_url.rstrip('/'), 'apiKey': control['apiKey']}
    write_private(path, control)
    return control


class PairingController:
    def __init__(self, bridge, state_dir, config_dir, control, *, clock=time.time, poll_interval=2):
        self.bridge, self.state_dir, self.config_dir = bridge, Path(state_dir), Path(config_dir)
        self.control, self.clock, self.poll_interval = control, clock, poll_interval
        self.lock = asyncio.Lock()
        self.task = None
        self.token = None
        self.png = b''
        self.deadline = 0
        self.state = {'state': 'idle', 'attemptId': None, 'expiresAt': None, 'code': None}

    def expected_identity(self):
        identities = set()
        for path in (self.config_dir / 'credentials.json', self.state_dir / 'account.json'):
            if path.exists():
                data = read_private(path)
                if not valid_secret(data.get('userId')):
                    raise ValueError('Invalid account configuration')
                identities.add(data['userId'])
        with self.bridge.app.state.db._get_connection() as conn:
            identities.update(str(row['user_id']) for row in conn.execute('SELECT DISTINCT user_id FROM api_keys WHERE revoked = 0'))
            tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            for table in ACCOUNT_TABLES:
                if table in tables:
                    identities.update(str(row['user_id']) for row in conn.execute(f'SELECT DISTINCT user_id FROM "{table}"'))
        if len(identities) > 1 or any(not valid_secret(identity) for identity in identities):
            raise ValueError('Ambiguous account identity')
        return next(iter(identities), None)

    def finish(self, state, code=None):
        self.state.update(state=state, code=code)
        if self.token:
            self.bridge.app.state.qr_sessions.pop(self.token, None)
        self.token, self.png = None, b''

    def status(self):
        if self.state['state'] == 'pending' and self.clock() >= self.deadline:
            self.finish('expired')
            if self.task:
                self.task.cancel()
        return dict(self.state)

    def admin_request(self, request, sessions):
        scope = dict(request.scope)
        # qr_start only needs qr_sessions from application state. Keep creation
        # attempt-local until validation succeeds, including across cancellation.
        scope['app'] = SimpleNamespace(state=SimpleNamespace(qr_sessions=sessions))
        scope['headers'] = [(name, value) for name, value in scope['headers'] if name.lower() != b'x-admin-key']
        scope['headers'].append((b'x-admin-key', os.environ['MI_FITNESS_ADMIN_KEY'].encode()))
        return Request(scope)

    async def start(self, request):
        async with self.lock:
            if self.status()['state'] == 'pending':
                return self.status()
            self.state = {'state': 'idle', 'attemptId': secrets.token_hex(16), 'expiresAt': None, 'code': None}
            try:
                self.expected_identity()
            except Exception:
                self.finish('error', 'configuration_invalid')
                return self.status()
            try:
                sessions = {}
                qr = await asyncio.wait_for(self.bridge.qr_start(self.admin_request(request, sessions), region='cn'), 45)
                token = qr['qr_token']
                session = sessions[token]
                expires = qr['expires_in']
                if not isinstance(expires, int) or isinstance(expires, bool) or expires < 1:
                    raise ValueError('Invalid expiration')
                self.png = session['png']
                if not isinstance(self.png, bytes) or not self.png.startswith(PNG) or len(self.png) > 512 * 1024:
                    raise ValueError('Invalid QR image')
                self.token = token
                self.bridge.app.state.qr_sessions[token] = session
                self.deadline = self.clock() + min(expires, 600)
                self.state.update(state='pending', expiresAt=datetime.fromtimestamp(self.deadline, timezone.utc).isoformat())
                self.task = asyncio.create_task(self.run(self.state['attemptId']))
            except Exception:
                self.finish('error', 'source_unavailable')
            return self.status()

    async def poll_cloud(self, session):
        # Mirror the pinned upstream QR protocol but check identity before issuing a key.
        try:
            async with httpx.AsyncClient(follow_redirects=False, timeout=15) as client:
                response = await client.get(session['lp'])
        except httpx.HTTPError:
            return None
        if response.status_code != 200 or len(response.content) > 65536:
            return None
        text = response.text
        if text.startswith(self.bridge.LOGIN_PREFIX):
            text = text[len(self.bridge.LOGIN_PREFIX):]
        try:
            body = json.loads(text)
        except (ValueError, TypeError):
            return None
        if not isinstance(body, dict) or body.get('code') != 0 or not body.get('userId'):
            return None
        return {'userId': str(body['userId']), 'passToken': body.get('passToken')}

    def save_connection(self, result, session):
        if not valid_secret(result.get('userId')) or not valid_secret(result.get('passToken')):
            self.finish('error', 'authorization_incomplete')
            return
        expected = self.expected_identity()
        if expected and not secrets.compare_digest(expected, result['userId']):
            self.finish('error', 'account_mismatch')
            return
        # This permanent pin remains if the transport credential is later removed.
        write_private(self.state_dir / 'account.json', {'userId': result['userId']})
        key = self.bridge._create_key_record(self.bridge.app.state.db, result['userId'], result['passToken'], session['region'], 'stridehub-qr')
        try:
            write_private(self.config_dir / 'credentials.json', {'baseUrl': self.control['baseUrl'], 'apiKey': key, 'userId': result['userId']})
        except Exception:
            with self.bridge.app.state.db._get_connection() as conn:
                conn.execute('UPDATE api_keys SET revoked = 1, pass_token = ? WHERE key = ?', ('', key))
                conn.commit()
            self.bridge.delete_api_key_secret(key)
            raise
        self.finish('success')

    async def run(self, attempt_id):
        try:
            while self.status()['state'] == 'pending' and self.state['attemptId'] == attempt_id:
                session = self.bridge.app.state.qr_sessions[self.token]
                result = await self.poll_cloud(session)
                # Cancellation/expiration must win over a late cloud confirmation.
                if self.status()['state'] != 'pending' or self.state['attemptId'] != attempt_id:
                    return
                if result:
                    self.save_connection(result, session)
                    return
                await asyncio.sleep(self.poll_interval)
        except asyncio.CancelledError:
            return
        except Exception:
            if self.state['attemptId'] == attempt_id:
                self.finish('error', 'source_unavailable')

    async def cancel(self, attempt_id):
        async with self.lock:
            if attempt_id != self.state['attemptId']:
                raise HTTPException(409, 'Pairing attempt changed')
            if self.status()['state'] == 'pending':
                self.finish('cancelled')
                if self.task:
                    self.task.cancel()
                    await self.task
            return self.status()

    async def close(self):
        if self.task and not self.task.done():
            self.task.cancel()
            await self.task
        self.finish('cancelled')


def install_pairing(bridge, state_dir, config_dir, control):
    controller = PairingController(bridge, state_dir, config_dir, control)
    api = FastAPI(openapi_url=None, docs_url=None, redoc_url=None)

    @api.middleware('http')
    async def private_control(request, call_next):
        supplied = request.headers.get('x-stridehub-control-key', '')
        if not secrets.compare_digest(supplied.encode(), control['apiKey'].encode()):
            return Response(status_code=401, headers={'Cache-Control': 'no-store, private'})
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store, private'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        return response

    @api.get('/status')
    async def status():
        return controller.status()

    @api.post('/start')
    async def start(request: Request):
        return await controller.start(request)

    @api.post('/cancel')
    async def cancel(request: Request):
        try:
            body = await request.json()
            attempt_id = body['attemptId']
            if not isinstance(attempt_id, str) or not re.fullmatch('[0-9a-f]{32}', attempt_id):
                raise ValueError()
        except Exception:
            raise HTTPException(400, 'Invalid pairing attempt') from None
        return await controller.cancel(attempt_id)

    @api.get('/qr')
    async def qr(attemptId: str):
        state = controller.status()
        if attemptId != state['attemptId']:
            raise HTTPException(409, 'Pairing attempt changed')
        if state['state'] != 'pending':
            raise HTTPException(410, 'QR unavailable')
        return Response(controller.png, media_type='image/png')

    bridge.app.mount('/api/stridehub/pair', api)
    return controller
