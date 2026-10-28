#!/usr/bin/env python3
"""Import a synthetic running fixture only into the guarded native acceptance backend.

No emulator operations or Zepp requests. Run from repository root; credentials
must belong to the disposable backend. Tokens stay in memory and are revoked.
"""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

ROOT = Path.cwd().resolve()
CONTAINER = 'stridehub-native-e2e'
BASE = 'http://127.0.0.1:8086'
OUT = ROOT / 'var/native-artifacts'
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TCX = 'http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2'
EXT = 'http://www.garmin.com/xmlschemas/ActivityExtension/v2'


def guard():
    info = json.loads(subprocess.check_output(['docker', 'inspect', CONTAINER]))[0]
    env = set(info['Config']['Env'])
    assert 'STRIDEHUB_NATIVE_E2E=1' in env, 'Missing isolated fixture marker'
    assert 'DATABASE_DIRECTORY=/var/www/var/native-e2e/db' in env, 'Wrong fixture database'
    assert 'IMPORT_MODE=files' in env, 'Fixture backend must use file import mode'
    mounts = {item['Destination']: Path(item['Source']).resolve() for item in info['Mounts']}
    assert mounts['/var/www'] == ROOT, 'Wrong worktree mount'
    assert mounts['/var/www/watch'] == (ROOT / 'var/native-e2e/watch').resolve(), 'Watch is not isolated'
    assert any(p['HostPort'] == '8086' and p['HostIp'] == '127.0.0.1'
               for p in info['NetworkSettings']['Ports']['8080/tcp']), 'Wrong local fixture endpoint'
    # Check the resolved live DB as well as the configured environment before writes.
    code = '''require "vendor/autoload.php";
new Symfony\\Component\\Dotenv\\Dotenv()->bootEnv(".env");
$kernel=new App\\Kernel("dev",true);$kernel->boot();
$path=$kernel->getContainer()->get("doctrine")->getConnection()->getParams()["path"];
if(realpath(dirname($path))!==realpath("/var/www/var/native-e2e/db"))exit(2);
$kernel->shutdown();'''
    subprocess.run(['docker', 'exec', CONTAINER, 'php', '-r', code], check=True,
                   stdout=subprocess.DEVNULL)


def element(parent, name, text=None, namespace=TCX, **attributes):
    child = ET.SubElement(parent, '{' + namespace + '}' + name, attributes)
    if text is not None:
        child.text = str(text)
    return child


def generate():
    ET.register_namespace('', TCX)
    ET.register_namespace('ext', EXT)
    today = dt.datetime.now(ZoneInfo('Asia/Shanghai')).date()
    start = dt.datetime.combine(today, dt.time(10, 30), ZoneInfo('Asia/Shanghai'))
    stamp = lambda value: value.astimezone(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    weights = [0.0 if i == 100 else 1 + .08 * math.sin(i / 17) for i in range(200)]
    scale = 5000 / (sum(weights) * 10)
    speeds = [weight * scale for weight in weights] + [weights[-1] * scale]
    root = ET.Element('{' + TCX + '}TrainingCenterDatabase')
    activity = element(element(root, 'Activities'), 'Activity', Sport='Running')
    element(activity, 'Id', stamp(start))
    lap = element(activity, 'Lap', StartTime=stamp(start))
    element(lap, 'TotalTimeSeconds', 2000)
    element(lap, 'DistanceMeters', 5000)
    element(lap, 'MaximumSpeed', f'{max(speeds):.6f}')
    element(lap, 'Calories', 360)
    element(element(lap, 'AverageHeartRateBpm'), 'Value', 145)
    track = element(lap, 'Track')
    distance = 0.0
    for index in range(201):
        if index:
            distance += speeds[index - 1] * 10
        point = element(track, 'Trackpoint')
        element(point, 'Time', stamp(start + dt.timedelta(seconds=index * 10)))
        position = element(point, 'Position')
        element(position, 'LatitudeDegrees', f'{31 + distance / 111320:.8f}')
        element(position, 'LongitudeDegrees', f'{121 + .001 * math.sin(index / 25):.8f}')
        element(point, 'AltitudeMeters', f'{100 + 8 * math.sin(index / 20):.3f}')
        element(point, 'DistanceMeters', f'{distance:.6f}')
        element(element(point, 'HeartRateBpm'), 'Value', 130 + round(index * .15))
        element(point, 'Cadence', 84 + round(2 * math.sin(index / 13)))
        tpx = element(element(point, 'Extensions'), 'TPX', namespace=EXT)
        element(tpx, 'Speed', f'{speeds[index]:.6f}', namespace=EXT)
        # No power/temperature samples: those metrics must remain unknown after import.
    element(activity, 'Notes', 'DEMO synthetic native running acceptance; ten-second pause at t=1000.')
    element(element(activity, 'Creator'), 'Name', 'DEMO native run fixture')
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / 'DEMO-native-run.TCX'
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)
    return today.isoformat(), path


class Client:
    def __init__(self):
        self.token = ''

    def request(self, path, method='GET', payload=None, data=None, content_type='application/json'):
        headers = {'Accept': 'application/json', 'Content-Type': content_type}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if payload is not None:
            data = json.dumps(payload).encode()
        request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        with HTTP.open(request, timeout=60) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}

    def upload(self, path):
        boundary = 'StrideHubSyntheticNativeRunFixture'
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        return self.request('/api/v1/mobile/imports', 'POST', data=data,
                            content_type='multipart/form-data; boundary=' + boundary)

    def revoke(self):
        if not self.token:
            return
        self.request('/mobile/v1/session', 'DELETE')
        try:
            self.request('/mobile/v1/session')
        except urllib.error.HTTPError as error:
            assert error.code == 401, 'Revoked fixture login must be rejected'
        else:
            raise AssertionError('Temporary fixture login was not revoked')
        self.token = ''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', type=Path, required=True,
                        help='Private JSON with disposable backend username/password')
    args = parser.parse_args()
    guard()
    credentials = json.loads(args.credentials.read_text())
    assert credentials['username'] == 'demo', 'Only the synthetic demo account is allowed'
    day, path = generate()
    client = Client()
    try:
        session = client.request('/mobile/v1/sessions', 'POST', {
            'username': credentials['username'], 'password': credentials['password'],
            'deviceName': 'DEMO synthetic run fixture verifier'})
        client.token = session['token']
        previous = {row['id'] for row in client.request('/api/v1/mobile/workouts?limit=100')['records']}
        receipt = client.upload(path)
        assert receipt['state'] in ('queued', 'success'), 'Fixture is not pending/imported; never resurrect deleted data'
        with (OUT / 'native-run-import.txt').open('w') as output:
            subprocess.run(['docker', 'exec', CONTAINER, 'php', 'bin/console', 'app:import:files'],
                           check=True, stdout=output, stderr=subprocess.STDOUT, timeout=180)
        deadline = time.monotonic() + 30
        while True:
            receipt = client.request('/api/v1/mobile/imports/' + receipt['id'])
            if receipt['state'] != 'queued' or time.monotonic() >= deadline:
                break
            time.sleep(.5)
        assert receipt['state'] == 'success' and receipt['activityId'], 'Real importer did not confirm fixture success'
        activity_id = receipt['activityId']
        encoded = urllib.parse.quote(activity_id, safe='')
        editor = client.request('/api/v1/mobile/activities/' + encoded)
        if editor['fields']['name'] != 'DEMO Native Run':
            client.request('/api/v1/mobile/activities/' + encoded, 'PUT',
                           {'version': editor['version'], 'name': 'DEMO Native Run'})
        overview = client.request('/api/v1/running/overview?from=' + day + '&to=' + day)
        detail = client.request('/api/v1/running/activities/' + encoded)
        analysis = detail['analysis']
        assert any(row['id'] == activity_id for row in overview['activities']['items'])
        assert overview['weekly'] and overview['monthly'] and overview['daily']
        assert any(row['activityId'] == activity_id for row in overview['bestEfforts']), 'No real calculated best efforts'
        assert len(analysis['timeline']) == 201 and analysis['scatter']
        assert analysis['zones'] and sum(zone['seconds'] for zone in analysis['zones']) > 0
        assert analysis['heartRateDistribution'] and analysis['coverage']['analyzedSeconds'] > 0
        assert analysis['coverage']['pausedSeconds'] == 10, 'Explicit pause must be visible in coverage'
        assert all(point['powerW'] is None for point in analysis['timeline']), 'Unknown power must remain unknown'
        assert detail['splits'] and detail['laps']
        assert abs(detail['activity']['distanceKm'] - 5) < .001
        after = {row['id'] for row in client.request('/api/v1/mobile/workouts?limit=100')['records']}
        assert previous <= after, 'An earlier demo workout disappeared'
        report = {
            'fixture': 'DEMO synthetic; actual TCX importer and analytics; no Zepp network',
            'date': day, 'activityId': activity_id, 'name': 'DEMO Native Run', 'importId': receipt['id'],
            'artifact': str(path.relative_to(ROOT)), 'importerLog': 'var/native-artifacts/native-run-import.txt',
            'distanceKm': detail['activity']['distanceKm'], 'movingSeconds': detail['activity']['movingSeconds'],
            'timeline': len(analysis['timeline']), 'scatter': len(analysis['scatter']),
            'zones': len(analysis['zones']), 'distributionBands': len(analysis['heartRateDistribution']),
            'coverage': analysis['coverage'], 'splits': len(detail['splits']), 'laps': len(detail['laps']),
            'bestEfforts': len([row for row in overview['bestEfforts'] if row['activityId'] == activity_id]),
            'weeklyPoints': len(overview['weekly']), 'monthlyPoints': len(overview['monthly']),
            'dailyPoints': len(overview['daily']), 'unknownPowerPreserved': True, 'earlierWorkoutIdsPreserved': True,
            'sourceMerged': False,
        }
        client.revoke()
        report['temporarySessionRevoked'] = True
        (OUT / 'native-run-fixture.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(report, ensure_ascii=False))
    finally:
        client.revoke()


if __name__ == '__main__':
    main()
