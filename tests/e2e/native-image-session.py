#!/usr/bin/env python3
"""Exercise production-image sessions across restarts using disposable Docker volumes.

Never mounts a host path or reuses an existing container, account or database.
Credentials remain in memory and are excluded from diagnostics.
"""
import argparse
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    prefix = 'stridehub-session-e2e-' + secrets.token_hex(6)
    name = prefix + '-web'
    volumes = []
    created = False
    password = secrets.token_urlsafe(32)
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    result = {'image': args.image, 'scope': 'new disposable Docker volumes only', 'checks': []}

    def command(arguments, **kwargs):
        completed = subprocess.run(arguments, capture_output=True, text=True, **kwargs)
        if completed.returncode:
            # Docker arguments can contain the disposable hash; omit arguments/output.
            raise RuntimeError('Docker operation failed; sensitive diagnostics omitted')
        return completed.stdout.strip()

    def address():
        info = json.loads(command(['docker', 'inspect', name]))[0]
        binding = info['NetworkSettings']['Ports']['8080/tcp'][0]
        assert binding['HostIp'] == '127.0.0.1', 'Test port must remain local'
        # Docker may assign a different ephemeral host port after every restart.
        return 'http://127.0.0.1:' + binding['HostPort']

    def request(path, method='GET', body=None, token=None):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if body is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base + path, headers=headers, method=method,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            response = http.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert not raw or 'application/json' in response.headers.get('Content-Type', ''), 'Expected JSON'
            return response.code, json.loads(raw or 'null'), response.headers

    def ready():
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                if request('/mobile/v1/session')[0] == 401:
                    return
            except (OSError, AssertionError, ValueError):
                pass
            time.sleep(1)
        raise AssertionError('Isolated production-image startup did not reach native401')

    def login():
        status, session, headers = request('/mobile/v1/sessions', 'POST', valid)
        assert status == 201, 'Expected native login201; got ' + str(status)
        assert session['apiVersion'] == 1 and 'mobile-workflows' in session['capabilities']
        assert session['username'] == valid['username']
        assert 'no-store' in headers.get('Cache-Control', '')
        return session['token']

    def passed(check):
        result['checks'].append(check)
        print('PASS: ' + check, flush=True)

    try:
        image = json.loads(command(['docker', 'image', 'inspect', args.image]))[0]
        result['localImageId'] = image['Id']
        result['revision'] = image['Config'].get('Labels', {}).get('org.opencontainers.image.revision')
        hashed = command(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'php', '-i', args.image,
                          '-r', 'echo password_hash(trim(stream_get_contents(STDIN)), PASSWORD_BCRYPT, ["cost"=>12]);'], input=password)
        mounts = []
        for kind, target in [('db', '/var/www/storage/database'), ('build', '/var/www/build'), ('watch', '/var/www/watch')]:
            volume = prefix + '-' + kind
            command(['docker', 'volume', 'create', '--label', 'stridehub.disposable-session-e2e=true', volume])
            volumes.append(volume)
            mounts.extend(['-v', volume + ':' + target])
        environment = {'APP_ENV': 'prod', 'APP_DEBUG': '0', 'APP_SECRET': secrets.token_hex(32),
                       'ADMIN_USERNAME': 'synthetic-session-e2e', 'ADMIN_PASSWORD_HASH': hashed,
                       'DATABASE_DIRECTORY': '/var/www/storage/database', 'TZ': 'Asia/Shanghai',
                       'ZEPP_CREDENTIALS_FILE': '/tmp/not-configured-session-e2e.json', 'FRANKENPHP_NUM_WORKERS': '1'}
        env_args = [item for key, value in environment.items() for item in ['-e', key + '=' + value]]
        command(['docker', 'run', '-d', '--name', name, '--label', 'stridehub.disposable-session-e2e=true',
                 '-p', '127.0.0.1::8080', *env_args, *mounts, args.image])
        created = True
        base = address()
        ready()
        passed('first startup anonymous session401')
        valid = {'username': environment['ADMIN_USERNAME'], 'password': password, 'deviceName': 'isolated image test'}
        invalid = {**valid, 'password': 'invalid synthetic password'}
        assert request('/mobile/v1/sessions', 'POST', invalid)[0] == 401
        first = login()
        assert request('/mobile/v1/session', token=first)[0] == 200
        passed('bcrypt cost12 login201 after failed401; authenticated session200')
        command(['docker', 'restart', '--time', '1', name])
        base = address()
        ready()
        assert request('/mobile/v1/session', token=first)[0] == 200
        second = login()
        assert request('/mobile/v1/session', 'DELETE', token=second)[0] == 204
        assert request('/mobile/v1/session', token=second)[0] == 401
        assert request('/mobile/v1/session', token=first)[0] == 200
        passed('restart anonymous401, persisted session200, new login201 and independent logout204/401')
        for _ in range(5):
            assert request('/mobile/v1/sessions', 'POST', invalid)[0] == 401
        command(['docker', 'restart', '--time', '1', name])
        base = address()
        ready()
        status, _, headers = request('/mobile/v1/sessions', 'POST', valid)
        assert status == 429 and headers.get('Retry-After')
        passed('five failures persist across restart and correct login receives429')
        assert request('/mobile/v1/session', token=first)[0] == 200
        assert request('/mobile/v1/session', 'DELETE', token=first)[0] == 204
        assert request('/mobile/v1/session', token=first)[0] == 401
        passed('existing session remains usable and revocable during login throttle')
        result['passed'] = True
    finally:
        if created:
            command(['docker', 'rm', '-f', name])
        for volume in volumes:
            command(['docker', 'volume', 'rm', volume])
        result['cleanup'] = {'containerRemoved': created, 'volumesRemoved': len(volumes), 'hostPathsMounted': False}
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
