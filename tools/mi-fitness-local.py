#!/usr/bin/env python3
"""Local Mi Fitness bridge + QR pairing. Install the pinned upstream package first.
No credentials are printed. All local state is confined to storage/mi-fitness.
"""
import argparse
import json
import os
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'storage' / 'mi-fitness'
BASE = 'http://127.0.0.1:8321'


def write_private(path, data):
    temporary = path.with_suffix('.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump(data, out)
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['serve', 'pair'])
    args = parser.parse_args()
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.action == 'serve':
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
        uvicorn.run(bridge.app, host='127.0.0.1', port=8321, access_log=False)
        return

    import httpx
    credentials = STATE / 'credentials.json'
    if credentials.exists():
        raise SystemExit('A connection already exists; keep it or explicitly remove it before pairing again.')
    with httpx.Client(base_url=BASE, timeout=25, follow_redirects=False) as client:
        response = client.post('/api/auth/qr/start', params={'region': 'cn'})
        response.raise_for_status()
        qr = response.json()
        png = client.get(qr['qr_image'])
        png.raise_for_status()
        image = STATE / 'pairing.png'
        image.write_bytes(png.content)
        print(f'Scan the QR image with the Xiaomi account / Mi Home app: {image}', flush=True)
        deadline = time.monotonic() + min(int(qr.get('expires_in', 300)), 600)
        try:
            while time.monotonic() < deadline:
                response = client.get('/api/auth/qr/poll', params={'token': qr['qr_token']})
                response.raise_for_status()
                result = response.json()
                if result.get('status') == 'confirmed':
                    if not result.get('api_key') or not result.get('user_id'):
                        raise SystemExit('Authorization did not return a usable bridge key; no StrideHub configuration was saved.')
                    write_private(credentials, {'baseUrl': BASE, 'apiKey': result['api_key'], 'userId': str(result['user_id'])})
                    print('Paired. Private StrideHub connection file saved; no health data synchronized yet.', flush=True)
                    return
                if result.get('status') == 'expired':
                    break
                time.sleep(2)
        finally:
            image.unlink(missing_ok=True)
    raise SystemExit('QR authorization expired; run pair again.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Upstream exception strings can contain URLs/tokens; never print them.
        raise SystemExit('Local bridge or Xiaomi authorization request failed. Check bridge availability and retry.') from None
