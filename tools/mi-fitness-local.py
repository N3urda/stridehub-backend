#!/usr/bin/env python3
"""Local Mi Fitness bridge + QR pairing. Install the pinned upstream package first.
No credentials are printed. All local state is confined to storage/mi-fitness.
"""
import argparse
from contextlib import asynccontextmanager
import os
from pathlib import Path
import time

from mi_fitness_pairing import install_pairing, prepare_control, read_private, write_private

ROOT = Path(__file__).resolve().parents[1]
STATE = Path(os.environ.get('MI_FITNESS_STATE_DIR', str(ROOT / 'storage' / 'mi-fitness')))
CONFIG = Path(os.environ.get('MI_FITNESS_CONFIG_DIR', str(STATE)))
BASE = os.environ.get('MI_FITNESS_BASE_URL', 'http://127.0.0.1:8321')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['serve', 'pair'])
    args = parser.parse_args()
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.action == 'serve':
        control = prepare_control(CONFIG, BASE)
        # Upstream administrative endpoints must stay protected on a private Docker network too.
        if not os.environ.get('MI_FITNESS_ADMIN_KEY'):
            os.environ['MI_FITNESS_ADMIN_KEY'] = control['apiKey']
        # Avoid creating platform-global config or reading another bridge's account.
        import mi_fitness_mcp.config as config
        config.get_config_path = lambda: STATE / 'bridge-config.json'
        if not config.get_config_path().exists():
            write_private(config.get_config_path(), {'mode': 'not_configured', 'region': 'cn',
                'database_path': str(STATE / 'bridge.sqlite'), 'logs_path': str(STATE / 'bridge.log'),
                'auto_sync_on_start': False, 'store_raw_payloads': False})
        from mi_fitness_records import install_records
        import uvicorn
        import mi_fitness_mcp.api as bridge
        from mi_fitness_sleep import StrideHubMiFitnessAdapter, install_details
        install_records(bridge)
        install_details(bridge, STATE / 'bridge.sqlite')
        bridge.MiFitnessCloudAdapter = StrideHubMiFitnessAdapter
        pairing = install_pairing(bridge, STATE, CONFIG, control)
        original_lifespan = bridge.app.router.lifespan_context

        @asynccontextmanager
        async def lifespan(app):
            async with original_lifespan(app):
                try:
                    yield
                finally:
                    await pairing.close()

        bridge.app.router.lifespan_context = lifespan
        uvicorn.run(bridge.app, host=os.environ.get('MI_FITNESS_HOST', '127.0.0.1'), port=8321, access_log=False)
        return

    import httpx
    control = read_private(CONFIG / 'pairing-control.json')
    with httpx.Client(base_url='http://127.0.0.1:8321', headers={'X-StrideHub-Control-Key': control['apiKey']}, timeout=50, follow_redirects=False) as client:
        response = client.post('/api/stridehub/pair/start', json={})
        response.raise_for_status()
        qr = response.json()
        if qr.get('state') != 'pending':
            raise SystemExit('Pairing could not start. Check the connector configuration and retry.')
        attempt = qr['attemptId']
        png = client.get('/api/stridehub/pair/qr', params={'attemptId': attempt})
        png.raise_for_status()
        image = STATE / 'pairing.png'
        image.write_bytes(png.content)
        print(f'Scan the QR image with the Xiaomi account / Mi Home app: {image}', flush=True)
        deadline = time.monotonic() + 610
        try:
            while time.monotonic() < deadline:
                response = client.get('/api/stridehub/pair/status')
                response.raise_for_status()
                result = response.json()
                if result.get('attemptId') != attempt:
                    raise SystemExit('The pairing attempt changed. Start again.')
                if result.get('state') == 'success':
                    print('Paired. Private StrideHub connection file saved; no health data synchronized yet.', flush=True)
                    return
                if result.get('state') != 'pending':
                    break
                time.sleep(2)
        finally:
            image.unlink(missing_ok=True)
    raise SystemExit('QR authorization was not completed; run pair again with the same account.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Upstream exception strings can contain URLs/tokens; never print them.
        raise SystemExit('Local bridge or Xiaomi authorization request failed. Check bridge availability and retry.') from None
