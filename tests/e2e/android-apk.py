#!/usr/bin/env python3
"""Real APK/UI acceptance against a dedicated emulator and disposable backend.

Requires ANDROID_HOME and an installed StrideHub APK on emulator-5556.
Never targets another emulator or the personal :8081 service. Credentials stay
in the ignored file and subprocess inputs; logs contain only assertions.
"""
import json
import hashlib
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import urllib.request

ROOT = Path.cwd()
ADB = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
SERIAL = 'emulator-5556'
PACKAGE = os.environ.get('STRIDEHUB_APK_PACKAGE', 'io.stridehub.app.debug')
assert PACKAGE in ('io.stridehub.app', 'io.stridehub.app.debug'), 'Only StrideHub packages may be tested'
BASE = 'http://127.0.0.1:8082'
CREDENTIALS = json.loads((ROOT / 'var/runtime/credentials.json').read_text())
SHOTS = ROOT / 'var/runtime/android-screenshots'
SHOTS.mkdir(exist_ok=True)
PASSED = 0


def adb(*args, binary=False, check=True):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, check=False)
    if check and result.returncode:
        raise AssertionError('ADB operation failed: ' + args[0])
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace')


def shell(command):
    return adb('shell', command)


def ui():
    for _ in range(3):
        output = shell('uiautomator dump /sdcard/stridehub-apk-test-ui.xml')
        if 'dumped to' in output:
            return ET.fromstring(shell('cat /sdcard/stridehub-apk-test-ui.xml'))
    raise AssertionError('Cannot capture emulator UI hierarchy')


def matching(tree, value, attribute=None):
    return [node for node in tree.iter('node') if (node.get(attribute) == value if attribute else value in (node.get('text'), node.get('content-desc')))]


def wait(value, timeout=30, attribute=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = matching(ui(), value, attribute)
        if nodes:
            return nodes[-1]
        time.sleep(0.25)
    raise AssertionError('Expected UI element did not appear: ' + value)


def wait_contains(value, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in ui().iter('node'):
            if value in node.get('text', '') or value in node.get('content-desc', ''):
                return node
        time.sleep(0.25)
    raise AssertionError('Expected page text did not appear: ' + value)


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    assert x2 > x1 and y2 > y1, 'Cannot tap a non-visible UI element'
    shell(f'input tap {(x1 + x2) // 2} {(y1 + y2) // 2}')


def tap(value, attribute=None):
    tap_node(wait(value, attribute=attribute))


def fill(label, value):
    tap(label, attribute='content-desc')
    shell('input keycombination 113 29')
    shell('input keyevent 67')
    shell('input text ' + shlex.quote(value.replace(' ', '%s')))
    shell('input keyevent 4')


def shot(name):
    (SHOTS / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def report(message):
    global PASSED
    PASSED += 1
    print('PASS ' + message, flush=True)


def restart():
    shell('am force-stop ' + PACKAGE)
    shell('am start -n ' + PACKAGE + '/io.stridehub.app.MainActivity')


def menu(choice):
    tap('更多操作', attribute='content-desc')
    tap(choice)


def scroll_to(value):
    for _ in range(8):
        found = [node for node in matching(ui(), value) if node.get('bounds') != '[0,0][0,0]' and int(re.findall(r'\d+', node.get('bounds'))[1]) < 2100]
        if found:
            return found[-1]
        shell('input swipe 530 1900 530 600 400')
    raise AssertionError('Cannot scroll to ' + value)


def seed_demo_athlete():
    # The upstream admin requires initial athlete setup before its upload page.
    database = ROOT / 'var/runtime/android-e2e-database/dreeve.db'
    assert str(database).endswith('/var/runtime/android-e2e-database/dreeve.db')
    with sqlite3.connect(database) as connection:
        for name, value in {'firstName': 'DEMO', 'lastName': 'Android acceptance', 'birthday': '1990-01-01', 'maxHeartRateFormula': 'fox', 'restingHeartRateFormula': 'heuristicAgeBased'}.items():
            connection.execute('INSERT INTO Setting(settingsGroup,name,value) VALUES (?,?,?) ON CONFLICT(settingsGroup,name) DO UPDATE SET value=excluded.value', ('general', name, json.dumps(value)))


def api_today():
    request = urllib.request.Request('http://localhost:8082/api/v1/training/today', headers={'Authorization': 'Bearer ' + CREDENTIALS['apiKey']})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def check_in():
    tap('今日')
    wait_contains('今日安排')
    # Scroll the actual WebView, then use the Android accessibility input and save button.
    shell('input swipe 530 1900 530 700 400')
    fields = [node for node in ui().iter('node') if node.get('class') == 'android.widget.EditText']
    assert fields, 'Today sleep input must be accessible'
    sleep = 7.3 if (api_today().get('checkIn') or {}).get('sleepHours') != 7.3 else 7.4
    tap_node(fields[0])
    shell('input keycombination 113 29')
    shell('input keyevent 67')
    shell('input text ' + str(sleep))
    shell('input keyevent 4')
    tap_node(scroll_to('保存今日状态'))
    wait_contains('已保存并核验')
    result = api_today()['checkIn']
    assert result['sleepHours'] == sleep, 'Actual WebView sleep value must persist in backend'
    assert result['pain'] is None and result['fatigue'] is None, 'Partial save must preserve unknown state'
    shot('native-saved-checkin.png')
    report('actual Today form writes sleep hours; independent backend GET confirms saved value and unknown pain/fatigue')


def upload_demo_file():
    watch = ROOT / 'var/runtime/android-e2e-watch'
    assert watch.is_dir(), 'Disposable container must mount private Android watch directory'
    before = {p.name for p in watch.iterdir() if p.is_file()}
    fixture = ROOT / 'var/runtime/DEMO-android-upload.gpx'
    payload = '<?xml version="1.0"?><gpx version="1.1" creator="StrideHub DEMO acceptance" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>DEMO Android file picker</name><trkseg><trkpt lat="31.2304" lon="121.4737"><time>2026-09-29T00:00:00Z</time></trkpt><trkpt lat="31.2314" lon="121.4747"><time>2026-09-29T00:05:00Z</time></trkpt></trkseg></trk></gpx>'
    fixture.write_text(payload)
    adb('push', str(fixture), '/sdcard/Download/' + fixture.name)
    tap('导入')
    wait('Dreeve | Admin panel')
    # API35 WebView sometimes omits the old admin page's main content from its
    # accessibility tree until the document picker returns. This coordinate was
    # visually verified inside its browse target on this fixed 1080x2400 Pixel7.
    # Success still requires the real picker and byte-identical backend upload.
    browse = matching(ui(), 'browse')
    if browse:
        tap_node(browse[-1])
    else:
        shell('input tap 540 1100')
    tree = ui()
    assert any(n.get('package') == 'com.google.android.documentsui' for n in tree.iter('node')), 'Actual Android document picker must open'
    shot('native-file-picker.png')
    if not matching(tree, fixture.name):
        tap('Show roots')
        tap('Downloads')
    tap(fixture.name)
    wait_contains(fixture.name)
    tap('Upload files')
    wait_contains('Uploaded')
    saved = [p for p in watch.iterdir() if p.is_file() and p.name not in before]
    assert len(saved) == 1 and saved[0].name.startswith('DEMO-android-upload') and saved[0].read_text() == payload
    shot('native-uploaded-file.png')
    report('real Android document picker content URI uploads exact GPX into isolated watch folder; import daemon is not run')


def session_and_recovery():
    tap('今日')
    wait_contains('今日安排')
    restart()
    wait('今日', timeout=45)
    wait_contains('今日安排')
    report('remembered authenticated cookie survives force-stop and app restart without re-entering password')
    adb('reverse', '--remove', 'tcp:8082')
    try:
        menu('刷新页面')
        wait_contains('连接失败')
    finally:
        adb('reverse', 'tcp:8082', 'tcp:8082')
    tap('重试')
    wait_contains('今日安排')
    report('network disconnect produces native retry and recovers authenticated page when connection returns')
    menu('切换服务器 / 账号')
    tap('继续')
    wait('连接并登录')
    assert wait('密码', attribute='content-desc').get('text') == '后端管理员密码'
    restart()
    wait_contains('登录已过期')
    fill('密码', CREDENTIALS['password'])
    tap('连接并登录')
    wait('今日', timeout=45)
    menu('退出登录')
    tap('继续')
    wait_contains('已退出本机登录')
    restart()
    wait_contains('登录已过期')
    report('switch account and native logout clear local auth; restart requires password again')


def main():
    assert os.environ.get('STRIDEHUB_TEST_URL') == 'http://localhost:8082', 'Explicit disposable backend URL required'
    assert (ROOT / 'var/runtime/android-e2e-database/dreeve.db').exists()
    assert 'device' in adb('get-state')
    personal = ROOT / 'var/runtime/dev-database/dreeve.db'
    fingerprint = hashlib.sha256(personal.read_bytes()).hexdigest()
    seed_demo_athlete()
    adb('reverse', 'tcp:8082', 'tcp:8082')
    # Reset only this explicitly selected StrideHub app on the dedicated acceptance emulator.
    shell('pm clear ' + PACKAGE)
    restart()
    wait('连接并登录')
    shot('native-connection.png')
    fill('服务地址', 'ftp://localhost:8082')
    tap('连接并登录')
    wait_contains('服务器地址必须以')
    fill('服务地址', BASE)
    tap('连接并登录')
    wait_contains('请使用 HTTPS')
    report('native setup rejects non-HTTP(S) URLs and requires explicit HTTP opt-in')
    tap('允许 HTTP（仅可信局域网）')
    fill('用户名', CREDENTIALS['username'])
    fill('密码', 'APK-E2E-deliberately-wrong-password')
    tap('连接并登录')
    wait_contains('登录未成功')
    assert wait('密码', attribute='content-desc').get('text') == '后端管理员密码'
    report('real Android WebView rejects incorrect password and returns to native setup with a cleared password')
    fill('密码', CREDENTIALS['password'])
    tap('连接并登录')
    wait('今日', timeout=45)
    wait_contains('今日安排')
    shot('native-today.png')
    report('real Android WebView submits Java-generated login script and opens authenticated Today')
    for label, expected in [('课表', '为下一次出发，做好准备。'), ('运动', '每一次出发，都有迹可循。'), ('导入', 'Dreeve | Admin panel')]:
        tap(label)
        wait_contains(expected)
    report('native Today, training, running and upload navigation tabs open server pages')
    upload_demo_file()
    check_in()
    session_and_recovery()
    assert hashlib.sha256(personal.read_bytes()).hexdigest() == fingerprint, 'Personal database must remain unchanged'
    print(json.dumps({'passed': PASSED, 'result': 'PASS', 'package': PACKAGE, 'coverage': 'real API35 emulator WebView and isolated backend; synthetic athlete/GPX fixtures only'}), flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error)
        for secret in CREDENTIALS.values():
            if isinstance(secret, str) and secret:
                message = message.replace(secret, '[redacted]')
        print('FAIL ' + message, file=sys.stderr)
        sys.exit(1)
