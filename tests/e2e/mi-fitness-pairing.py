"""Offline QR lifecycle and account-isolation checks. No Xiaomi requests are made."""
import asyncio
import json
from pathlib import Path
import sqlite3
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx
from fastapi import FastAPI

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from mi_fitness_pairing import PNG, install_pairing, prepare_control, read_private, write_private


class Database:
    def __init__(self, path):
        self.path = path

    def _get_connection(self):
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        return conn


class PairingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = TemporaryDirectory()
        self.state = Path(self.directory.name)
        self.config = self.state / 'config'
        self.control = prepare_control(self.config, 'http://mi-fitness:8321')
        self.env = patch.dict('os.environ', {'MI_FITNESS_ADMIN_KEY': self.control['apiKey']})
        self.env.start()
        self.db = Database(self.state / 'test.sqlite')
        with self.db._get_connection() as conn:
            conn.execute('CREATE TABLE api_keys (key TEXT, user_id TEXT, pass_token TEXT, revoked INTEGER DEFAULT 0)')
            conn.execute('CREATE TABLE sleep_sessions (user_id TEXT)')
        self.app = FastAPI()
        self.app.state.db, self.app.state.qr_sessions = self.db, {}
        self.starts = 0
        self.created = []

        async def qr_start(request, region):
            self.assertEqual(request.headers['x-admin-key'], self.control['apiKey'])
            self.starts += 1
            token = str(self.starts)
            request.app.state.qr_sessions[token] = {'png': PNG + b'fake QR', 'lp': 'https://example.invalid/private-token', 'region': region}
            return {'qr_token': token, 'expires_in': 300}

        def create(db, user_id, pass_token, region, label):
            key = 'mif_sk_test_' + str(len(self.created))
            self.created.append((user_id, pass_token))
            with db._get_connection() as conn:
                conn.execute('INSERT INTO api_keys (key,user_id,pass_token) VALUES (?,?,?)', (key, user_id, pass_token))
            return key

        self.bridge = SimpleNamespace(app=self.app, qr_start=qr_start, _create_key_record=create,
                                      delete_api_key_secret=lambda key: None, LOGIN_PREFIX='&&&START&&&')
        self.controller = install_pairing(self.bridge, self.state, self.config, self.control)
        self.now = [1700000000]
        self.controller.clock = lambda: self.now[0]
        self.queue = asyncio.Queue()

        async def poll(session):
            return await self.queue.get()

        self.controller.poll_cloud = poll
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://test',
                                       headers={'X-StrideHub-Control-Key': self.control['apiKey']})

    async def asyncTearDown(self):
        await self.controller.close()
        await self.client.aclose()
        self.env.stop()
        self.directory.cleanup()

    async def start(self):
        response = await self.client.post('/api/stridehub/pair/start', json={})
        self.assertEqual(response.status_code, 200)
        return response.json()

    async def confirmed(self, user_id='account-one', pass_token='private-cloud-token'):
        await self.queue.put({'userId': user_id, 'passToken': pass_token})
        await asyncio.wait_for(self.controller.task, 1)
        return (await self.client.get('/api/stridehub/pair/status')).json()

    async def test_control_key_required_and_no_cache(self):
        for headers in ({}, {'X-StrideHub-Control-Key': 'incorrect'}):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://test') as anonymous:
                response = await anonymous.get('/api/stridehub/pair/status', headers=headers)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.headers['cache-control'], 'no-store, private')
        response = await self.client.get('/api/stridehub/pair/status')
        self.assertEqual(response.json()['state'], 'idle')

    async def test_confirmed_pair_writes_private_config_without_exposing_secrets(self):
        state = await self.start()
        self.assertEqual(state['state'], 'pending')
        self.assertRegex(state['attemptId'], '^[0-9a-f]{32}$')
        self.assertEqual((await self.start())['attemptId'], state['attemptId'])
        self.assertEqual(self.starts, 1)
        qr = await self.client.get('/api/stridehub/pair/qr', params={'attemptId': state['attemptId']})
        self.assertEqual(qr.content, PNG + b'fake QR')
        result = await self.confirmed()
        self.assertEqual(result['state'], 'success')
        credentials = read_private(self.config / 'credentials.json')
        self.assertEqual(credentials['baseUrl'], 'http://mi-fitness:8321')
        self.assertEqual(credentials['userId'], 'account-one')
        self.assertEqual((self.config / 'credentials.json').stat().st_mode & 0o777, 0o600)
        for secret in ('account-one', 'private-cloud-token', credentials['apiKey'], self.control['apiKey'], 'private-token'):
            self.assertNotIn(secret, str(result))
        self.assertEqual(self.app.state.qr_sessions, {})
        self.assertEqual((await self.client.get('/api/stridehub/pair/qr', params={'attemptId': state['attemptId']})).status_code, 410)

    async def test_different_account_cannot_issue_key_or_replace_existing_credential(self):
        old = {'baseUrl': 'http://mi-fitness:8321', 'apiKey': 'old-key', 'userId': 'account-one'}
        write_private(self.config / 'credentials.json', old)
        await self.start()
        result = await self.confirmed('account-two')
        self.assertEqual(result['code'], 'account_mismatch')
        self.assertEqual(read_private(self.config / 'credentials.json'), old)
        self.assertEqual(self.created, [])

    async def test_same_account_renewal_and_permanent_pin(self):
        await self.start()
        self.assertEqual((await self.confirmed())['state'], 'success')
        first = read_private(self.config / 'credentials.json')
        await self.start()
        self.assertEqual((await self.confirmed())['state'], 'success')
        self.assertNotEqual(read_private(self.config / 'credentials.json')['apiKey'], first['apiKey'])
        (self.config / 'credentials.json').unlink()
        with self.db._get_connection() as conn:
            conn.execute('UPDATE api_keys SET revoked=1')
        await self.start()
        self.assertEqual((await self.confirmed('account-two'))['code'], 'account_mismatch')
        self.assertFalse((self.config / 'credentials.json').exists())

    async def test_existing_health_data_pins_account_even_without_credentials(self):
        with self.db._get_connection() as conn:
            conn.execute('INSERT INTO sleep_sessions VALUES (?)', ('account-one',))
        await self.start()
        self.assertEqual((await self.confirmed('account-two'))['code'], 'account_mismatch')
        self.assertEqual(self.created, [])

    async def test_expiration_retry_and_stale_requests(self):
        first = await self.start()
        self.now[0] += 301
        self.assertEqual((await self.client.get('/api/stridehub/pair/status')).json()['state'], 'expired')
        second = await self.start()
        self.assertNotEqual(first['attemptId'], second['attemptId'])
        self.assertEqual((await self.client.get('/api/stridehub/pair/qr', params={'attemptId': first['attemptId']})).status_code, 409)
        self.assertEqual((await self.client.post('/api/stridehub/pair/cancel', json={'attemptId': first['attemptId']})).status_code, 409)
        self.assertEqual(self.controller.status()['state'], 'pending')
        self.assertEqual((await self.client.post('/api/stridehub/pair/cancel', json={'attemptId': second['attemptId']})).json()['state'], 'cancelled')
        self.assertFalse((self.config / 'credentials.json').exists())

    async def test_cancel_wins_over_late_confirmation(self):
        entered = asyncio.Event()

        async def late_poll(session):
            entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                return {'userId': 'account-one', 'passToken': 'late-token'}

        self.controller.poll_cloud = late_poll
        state = await self.start()
        await entered.wait()
        await self.client.post('/api/stridehub/pair/cancel', json={'attemptId': state['attemptId']})
        self.assertEqual(self.controller.status()['state'], 'cancelled')
        self.assertEqual(self.created, [])
        self.assertFalse((self.config / 'credentials.json').exists())

    async def test_missing_authorization_keeps_existing_config(self):
        await self.start()
        self.assertEqual((await self.confirmed(pass_token=None))['code'], 'authorization_incomplete')
        self.assertEqual(self.created, [])

    async def test_bad_existing_config_fails_before_cloud_request(self):
        write_private(self.config / 'credentials.json', {'userId': None})
        self.assertEqual((await self.start())['code'], 'configuration_invalid')
        self.assertEqual(self.starts, 0)

    async def test_source_failure_is_redacted(self):
        async def failed(request, region):
            request.app.state.qr_sessions['orphan-token'] = {'png': PNG}
            raise RuntimeError('private-cloud-token https://example.invalid/private-token')
        self.bridge.qr_start = failed
        state = await self.start()
        self.assertEqual(state['code'], 'source_unavailable')
        self.assertNotIn('private', str(state))
        self.assertEqual(self.app.state.qr_sessions, {})


class PinnedUpstreamIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_app_lifespan_qr_protocol_and_issued_key(self):
        import mi_fitness_mcp.api as upstream
        from mi_fitness_mcp.config import Config

        original_client = httpx.AsyncClient

        def cloud_response(request):
            if str(request.url).startswith(upstream.LOGIN_URL_ENDPOINT):
                body = {'qr': 'https://xiaomi-test.invalid/qr', 'lp': 'https://xiaomi-test.invalid/poll', 'timeout': 300}
                return httpx.Response(200, text=upstream.LOGIN_PREFIX + json.dumps(body))
            if str(request.url) == 'https://xiaomi-test.invalid/qr':
                return httpx.Response(200, content=PNG + b'mocked cloud QR')
            if str(request.url) == 'https://xiaomi-test.invalid/poll':
                body = {'code': 0, 'userId': 'synthetic-owner', 'passToken': 'synthetic-secret'}
                return httpx.Response(200, text=upstream.LOGIN_PREFIX + json.dumps(body))
            raise AssertionError('Unexpected mock cloud request')

        def cloud_client(*args, **kwargs):
            return original_client(*args, **kwargs, transport=httpx.MockTransport(cloud_response))

        with TemporaryDirectory() as directory:
            state = Path(directory)
            control = prepare_control(state, 'http://mi-fitness:8321')
            config = Config(database_path=state / 'bridge.sqlite', logs_path=state / 'bridge.log', auto_sync_on_start=False)
            env = {'MI_FITNESS_ALLOWED_HOSTS': 'mi-fitness,127.0.0.1', 'MI_FITNESS_ADMIN_KEY': control['apiKey'], 'MI_FITNESS_API_KEY': 'upstream-gate-secret'}
            controller = install_pairing(upstream, state, state, control)
            async with original_client(transport=httpx.ASGITransport(app=upstream.app), base_url='http://mi-fitness:8321', headers={'X-StrideHub-Control-Key': control['apiKey']}) as client:
                with patch.dict('os.environ', env), patch.object(upstream, 'load_config', return_value=config), patch.object(upstream, 'store_api_key_secret', return_value=False), patch.object(upstream, 'load_api_key_secret', return_value=None), patch.object(upstream, 'delete_api_key_secret'), patch('httpx.AsyncClient', side_effect=cloud_client):
                    async with upstream.app.router.lifespan_context(upstream.app):
                        try:
                            response = await client.post('/api/stridehub/pair/start', json={})
                            self.assertEqual(response.status_code, 200)
                            self.assertEqual(response.json()['state'], 'pending')
                            await asyncio.wait_for(controller.task, 1)
                            result = (await client.get('/api/stridehub/pair/status')).json()
                            self.assertEqual(result['state'], 'success')
                            self.assertNotIn('synthetic', str(result))
                            credential = read_private(state / 'credentials.json')
                            self.assertEqual(credential['userId'], 'synthetic-owner')
                            # Prove the generated credential works on the real existing data API.
                            account = await client.get('/api/status?deep=false', headers={'X-API-Key': credential['apiKey']})
                            self.assertEqual(account.status_code, 200)
                            self.assertEqual(account.json()['user_id'], 'synthetic-owner')
                            self.assertEqual((await client.post('/api/auth/qr/start', headers={'X-API-Key': 'upstream-gate-secret'})).status_code, 401)
                            self.assertEqual(upstream.app.state.qr_sessions, {})
                        finally:
                            await controller.close()


if __name__ == '__main__':
    unittest.main()
