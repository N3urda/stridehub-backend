#!/usr/bin/env python3
"""Native Android UI acceptance over public HTTPS to the disposable NAS fixture only.

No NAS/container administration, application-data clearing, TLS bypass, or service pause.
A matching public instance marker AND authenticated fixture marker are required before UI writes.
"""
from __future__ import annotations

import argparse
import hashlib
from contextlib import contextmanager
from datetime import date, timedelta
import ast
import types
import json
import math
import os
from pathlib import Path
import re
import shlex
import sqlite3
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
PREFIX = '/__stridehub_e2e_20261026'
SERIAL = 'emulator-5556'
AVD = 'stridehub_native'
PACKAGE = 'io.stridehub.app.debug'
SCENARIOS = ('navigation', 'meal', 'plans', 'running', 'dates', 'draft', 'weighing', 'offline', 'sync', 'logout')


class SafeFailure(RuntimeError):
    """Messages are deliberately authored metadata, never raw server/UI/credential contents."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_base(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or '.' not in parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path.rstrip('/') != PREFIX
            or '%' in parsed.path or '\\' in value):
        raise SafeFailure('BASE must be public HTTPS with the exact disposable-instance path ' + PREFIX)
    try:
        port = parsed.port
    except ValueError:
        raise SafeFailure('BASE contains an invalid public HTTPS port') from None
    authority = parsed.hostname.lower() + ((':'+str(port)) if port not in (None,443) else '')
    return urllib.parse.urlunsplit((parsed.scheme, authority, PREFIX, '', ''))


def write_private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


class HttpsApi:
    def __init__(self, base, timeout=45):
        self.base = validate_base(base)
        self.timeout = timeout
        self.token = ''
        self.events = []
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def request(self, path, method='GET', body=None, authenticated=True):
        if not path.startswith('/') or path.startswith('//'):
            raise SafeFailure('Refusing an API path outside the owned installation')
        headers = {'Accept': 'application/json', 'Cache-Control': 'no-store'}
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode('utf-8')
        if authenticated and self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request(self.base + path, headers=headers, method=method, data=data)
        started = time.monotonic()
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read(16 * 1024 * 1024 + 1)
                self.events.append({'method': method, 'path': path.split('?')[0],
                                    'status': response.status, 'seconds': round(time.monotonic()-started, 3)})
                if len(raw) > 16 * 1024 * 1024:
                    raise SafeFailure('API observer response exceeded the bounded fixture size')
                if not raw:
                    return {}
                try:
                    return json.loads(raw)
                except (ValueError, UnicodeError):
                    raise SafeFailure('Expected JSON from the public fixture API; no raw response logged') from None
        except urllib.error.HTTPError as error:
            self.events.append({'method': method, 'path': path.split('?')[0], 'status': error.code})
            raise SafeFailure('Public fixture API returned HTTP ' + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError, ssl.SSLError):
            raise SafeFailure('Public HTTPS request failed or timed out; TLS validation was not bypassed') from None


def legacy_ui_module():
    # Reuse only these four UI helpers, not the old script's global endpoint/device configuration
    # or mutation scenarios. This also permits --adb without requiring ANDROID_HOME at import.
    source = REPO/'tests/e2e/native-android.py'
    parsed = ast.parse(source.read_text(), filename=str(source))
    wanted = {'ui', 'texts', 'matches', 'point'}
    definitions = [node for node in parsed.body if isinstance(node, ast.FunctionDef) and node.name in wanted]
    if {node.name for node in definitions} != wanted:
        raise SafeFailure('Reusable native UI helper contract changed; update this harness first')
    module = types.ModuleType('stridehub_native_ui_helpers')
    module.__dict__.update(ET=ET, re=re, time=time)
    exec(compile(ast.Module(body=definitions, type_ignores=[]), str(source), 'exec'), module.__dict__)
    return module


class NativeUi:
    def __init__(self, adb, out):
        self.adb_path = str(adb)
        self.out = out
        self.legacy = legacy_ui_module()
        self.legacy.adb = self.adb
        self.legacy.OUT = out
        self.last_stage = 'guard'

    def adb(self, *arguments, timeout=45):
        try:
            return subprocess.check_output([self.adb_path, '-s', SERIAL, *arguments],
                                           stderr=subprocess.STDOUT, timeout=timeout)
        except (OSError, subprocess.SubprocessError):
            # Never interpolate a command, stderr, input text or credential into diagnostics.
            raise SafeFailure('ADB operation failed; command arguments and output omitted') from None

    def tree(self):
        try:
            return self.legacy.ui()
        except Exception:
            raise SafeFailure('Native UI hierarchy could not be read or contained a browser surface') from None

    def texts(self, root=None):
        return self.legacy.texts(root if root is not None else self.tree())

    def matches(self, root, label, partial=False):
        return self.legacy.matches(root, label, partial)

    def visible(self, label, partial=False):
        return bool(self.matches(self.tree(), label, partial))

    def scroll_signature(self,root):
        # Use visible content, excluding changing status-bar clocks/network indicators.
        return tuple((node.get('text',''),node.get('content-desc',''),node.get('bounds',''))
                     for node in root.iter('node')
                     if node.get('package') in (None,PACKAGE))

    def slow_scroll(self,direction,distance=400):
        start,end=('1500',str(1500-distance)) if direction=='down' else ('1000',str(1000+distance))
        self.adb('shell','input','swipe','540',start,'540',end,'650')
        time.sleep(.25)

    def seek(self,label,partial=False,attempts=90):
        # A long fast fling can skip a short heading entirely. Scan small overlapping
        # viewports, detect an unchanged boundary, then search back exactly once.
        for direction in ('down','up'):
            previous=None;unchanged=0
            for _ in range(attempts):
                root=self.tree()
                found=[node for node in self.matches(root,label,partial) if node.get('enabled','true')!='false']
                if found:return found[-1]
                signature=self.scroll_signature(root)
                unchanged=unchanged+1 if signature==previous else 0
                if unchanged>=2:break
                previous=signature
                self.slow_scroll(direction)
        raise SafeFailure('Expected native control was not found after a bounded reversible scroll search')

    def show_section(self,label):
        # A heading near the bottom is insufficient screenshot evidence for its chart.
        # Bring it into the upper content area using slow, bounded adjustments.
        node=self.seek(label)
        for _ in range(8):
            _,top,_,bottom=map(int,re.findall(r'\d+',node.get('bounds','')))
            center=(top+bottom)//2
            if center<=620:return
            self.slow_scroll('down',min(400,max(120,center-420)))
            node=self.seek(label)
        raise SafeFailure('Native section could not be aligned with its content visible')

    def tap(self, label, scroll=False, partial=False, attempts=None):
        if scroll:
            node=self.seek(label,partial,attempts or 90)
            self.adb('shell','input','tap',*self.legacy.point(node))
            time.sleep(.4)
            return
        for _ in range(attempts or 5):
            matches=[node for node in self.matches(self.tree(),label,partial) if node.get('enabled','true')!='false']
            if matches:
                self.adb('shell','input','tap',*self.legacy.point(matches[-1]))
                time.sleep(.4)
                return
            time.sleep(.3)
        raise SafeFailure('Expected native control was not actionable at the current step')

    def tap_any(self, labels, scroll=False):
        for _ in range(45 if scroll else 6):
            root = self.tree()
            for label in labels:
                if self.matches(root, label):
                    return self.tap(label)
            if scroll:
                self.slow_scroll('down')
            else:
                time.sleep(.25)
        raise SafeFailure('Expected native dialog action was not visible')

    def checkbox(self, caption, checked):
        # Caption taps bring the row into view; the checkbox itself owns the native action.
        self.tap(caption, scroll=True)
        root = self.tree()
        labels = self.matches(root, caption)
        if not labels:
            raise SafeFailure('Expected native login choice was not visible')
        caption_y = int(self.legacy.point(labels[-1])[1])
        boxes = [node for node in root.iter('node') if node.get('class') == 'android.widget.CheckBox']
        boxes.sort(key=lambda node: abs(int(self.legacy.point(node)[1])-caption_y))
        if not boxes or abs(int(self.legacy.point(boxes[0])[1])-caption_y) > 110:
            raise SafeFailure('Expected native login choice could not be paired with its control')
        if (boxes[0].get('checked') == 'true') != checked:
            self.adb('shell', 'input', 'tap', *self.legacy.point(boxes[0]))
            time.sleep(.25)

    def wait(self, label, timeout=45, partial=False):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if self.visible(label, partial):
                return
            time.sleep(.35)
        raise SafeFailure('Expected native screen/state did not appear before timeout')

    def scroll_to_top(self, heading, attempts=45):
        # Native pages deliberately restore scroll position. Seek their first content heading
        # by moving toward the top, without tapping data, resetting drafts or changing tabs.
        for _ in range(attempts):
            if self.visible(heading):return
            self.adb('shell','input','swipe','540','600','540','1600','220')
            time.sleep(.2)
        if not self.visible(heading):
            raise SafeFailure('Expected native page heading was not found toward the top')

    def fill(self, label, value):
        if not value.isascii():
            raise SafeFailure('This ADB harness requires ASCII fixture input; do not change a production password')
        # Reuse native label/focus lookup, but do not rely on Ctrl+A: some Android/IME
        # combinations ignore it. Every replacement is cleared and read back before submit.
        def fields(root):
            return [node for node in root.iter('node') if node.get('class') == 'android.widget.EditText'
                    and (node.get('content-desc') == label or any(label == child.get('text') for child in node))]
        for _ in range(40):
            candidates = fields(self.tree())
            if candidates:
                field = candidates[-1]
                x, y, xx, yy = map(int, re.findall(r'\d+', field.get('bounds')))
                if yy-y >= 90 and y >= 220 and yy <= 1650:
                    break
                if y < 220:
                    self.adb('shell', 'input', 'swipe', '540', '500', '540', '1000', '220')
                    continue
            self.adb('shell', 'input', 'swipe', '540', '1400', '540', '850', '220')
        else:
            raise SafeFailure('Expected native input could not be placed fully on screen')
        self.adb('shell', 'input', 'tap', *self.legacy.point(field))
        focused = [node for node in fields(self.tree()) if node.get('focused') == 'true']
        if len(focused) != 1:
            raise SafeFailure('Expected native input did not receive unambiguous focus')
        old_length = len(focused[0].get('text','').encode('utf-16-le'))//2
        if old_length > 16384:
            raise SafeFailure('Native input exceeds the bounded fixture replacement size')
        self.adb('shell', 'input', 'keyevent', '123')  # MOVE_END, no Ctrl chord needed.
        # Delete both sides of the cursor. This also handles a multiline field where MOVE_END
        # means end of the current line. Batching avoids one ADB process per character.
        for key in ('67','112'):  # DEL, FORWARD_DEL
            remaining = old_length+8
            while remaining:
                count = min(remaining,128)
                self.adb('shell','input','keyevent',*([key]*count))
                remaining -= count
        cleared = [node for node in fields(self.tree()) if node.get('focused') == 'true']
        if len(cleared) != 1 or cleared[0].get('text','') != '':
            raise SafeFailure('Native input could not be fully cleared; replacement was not entered')
        for index, part in enumerate(value.split('%')):
            if index:
                self.adb('shell', 'input text ' + shlex.quote('%'))
            if part:
                self.adb('shell', 'input text ' + shlex.quote(part.replace(' ', '%s')))
        time.sleep(.35)
        if label == '密码':
            # Exact comparison needs the native visibility toggle, not bullet-count guessing.
            # The synthetic value stays in memory; immediately mask again and remove the
            # transient hierarchy file, even when validation fails. No screenshot occurs here.
            root = self.tree()
            show = self.matches(root,'显示密码')
            if len(show) != 1:
                raise SafeFailure('Native password must start masked with its visibility control available')
            toggle = self.legacy.point(show[0])
            self.adb('shell','input','tap',*toggle)
            try:
                time.sleep(.2)
                entered = fields(self.tree())
                if len(entered) != 1 or entered[0].get('text','') != value:
                    raise SafeFailure('Native input readback did not equal its intended fixture value')
            finally:
                try:
                    self.adb('shell','input','tap',*toggle)
                    time.sleep(.2)
                finally:
                    self.adb('shell','rm','-f','/sdcard/stridehub-native.xml')
            if not self.matches(self.tree(),'显示密码'):
                raise SafeFailure('Native password masking was not restored after private validation')
        else:
            entered = fields(self.tree())
            if len(entered) != 1 or entered[0].get('text','') != value:
                raise SafeFailure('Native input readback did not equal its intended fixture value')
        if 'mInputShown=true' in self.adb('shell', 'dumpsys', 'input_method').decode(errors='replace'):
            self.adb('shell', 'input', 'keyevent', '4')

    def tab(self, label):
        self.tap(label)
        for _ in range(15):
            if not self.visible('返回'):
                headings={'总览':'最近的真实记录','身体':'身体与恢复','运动':'每一次运动','计划':'计划与日常'}
                if label in headings:self.scroll_to_top(headings[label])
                return
            self.tap('返回')
        raise SafeFailure('Native tab history did not return to its root')

    def screenshot(self, name):
        # Screenshots are permitted only after both fixture identity guards passed.
        path = self.out/(name+'.png')
        path.write_bytes(self.adb('exec-out', 'screencap', '-p'))
        path.chmod(0o600)
        return path.name

    def local_state(self):
        # Android may use WAL. Copy its journal as well, but never retain any raw local DB
        # in the report. A torn copy is retried rather than interpreted as missing rows.
        for attempt in range(3):
            with tempfile.TemporaryDirectory(prefix='stridehub-nas-local-check-') as directory:
                path = Path(directory)/'local.db'
                path.write_bytes(self.adb('exec-out', 'run-as', PACKAGE, 'cat', 'databases/tinyyumi.db'))
                try:
                    journal = self.adb('exec-out', 'run-as', PACKAGE, 'cat', 'databases/tinyyumi.db-wal')
                except SafeFailure:
                    journal = b''
                if journal:
                    Path(str(path)+'-wal').write_bytes(journal)
                try:
                    db = sqlite3.connect(path)
                    db.row_factory = sqlite3.Row
                    try:
                        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                            raise sqlite3.DatabaseError()
                        return {
                            'measurements':[dict(row) for row in db.execute('SELECT id,weight,upload_uuid,local_day,source FROM measurements ORDER BY id')],
                            'queue':[dict(row) for row in db.execute('SELECT measurement_id,uuid,binding,server,payload,status FROM sync_queue ORDER BY measurement_id')]
                        }
                    finally:
                        db.close()
                except sqlite3.Error:
                    if attempt == 2:
                        raise SafeFailure('A consistent private local weighing snapshot could not be read') from None
            time.sleep(.2)

    def local_snapshot(self):
        return [(row['id'],row['weight'],row['upload_uuid']) for row in self.local_state()['measurements']]


@contextmanager
def android_offline(ui):
    original = {}
    for service, key in [('wifi', 'wifi_on'), ('data', 'mobile_data')]:
        state = ui.adb('shell', 'settings', 'get', 'global', key).decode().strip()
        if state not in ('0', '1'):
            raise SafeFailure('Cannot safely snapshot Android network state for restoration')
        original[service] = state
    try:
        for service in original:
            ui.adb('shell', 'svc', service, 'disable')
        yield
    finally:
        failures = []
        for service, state in original.items():
            try:
                ui.adb('shell', 'svc', service, 'enable' if state == '1' else 'disable')
            except SafeFailure:
                failures.append(service)
        if failures:
            raise SafeFailure('Android network restoration failed; restore Wi-Fi/data before continuing')


def require_empty_pairing_history(state):
    if state.get('measurements') != [] or state.get('queue') != []:
        raise SafeFailure('Weighing pairing requires empty local measurements AND queue; refusing any history upload or deletion')


def exact_weight(actual,expected):
    return isinstance(actual,(int,float)) and not isinstance(actual,bool) and math.isfinite(actual) and abs(actual-expected)<=1e-9


def require_single_manual_measurement(state,weight):
    rows=state.get('measurements',[])
    if len(rows)!=1 or not exact_weight(rows[0].get('weight'),weight) or rows[0].get('source') not in ('manual','手工录入') or not re.fullmatch(r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}',rows[0].get('upload_uuid','')):
        raise SafeFailure('Expected exactly one new manual fixture measurement with its stable UUID')
    return rows[0]


def require_unique_server_capture(rows,measurement_id,weight,device_id):
    matches=[row for row in rows if row.get('measurementId')==measurement_id]
    if len(matches)!=1 or matches[0].get('deleted') is not False or matches[0].get('deviceId')!=device_id or not exact_weight(matches[0].get('capture',{}).get('weightKg'),weight):
        raise SafeFailure('Public collection readback did not match one exact live measurement UUID/device/weight')
    return matches[0]


class NasAcceptance:
    def __init__(self, args):
        self.args = args
        self.base = validate_base(args.base)
        self.credentials = json.loads(args.credentials.read_text())
        required = ('username', 'password', 'instanceId')
        if any(not isinstance(self.credentials.get(key), str) or not self.credentials[key] for key in required):
            raise SafeFailure('Credentials JSON requires non-empty username/password/instanceId')
        self.fixtures = json.loads(args.fixtures.read_text()) if args.fixtures else {}
        self.run_id = time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
        self.out = args.report_dir.resolve()/self.run_id
        self.out.mkdir(parents=True, mode=0o700)
        self.api = HttpsApi(self.base, args.http_timeout)
        self.ui = NativeUi(args.adb, self.out)
        self.identity_verified = False
        self.ui_identity_verified = False
        self.report = {'runId': self.run_id, 'base': self.base, 'instanceId': self.credentials['instanceId'],
                       'package': PACKAGE, 'serial': SERIAL, 'checks': [], 'apiEvents': self.api.events,
                       'containsCredentials': False, 'productionInstanceAccessed': False}
        self.plan_id = self.fixtures.get('planId', 'native-demo-plan')
        self.session_id = self.fixtures.get('sessionId', 'native-demo-session')
        if any(not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',value) for value in (self.plan_id,self.session_id)):
            raise SafeFailure('Fixture plan/session IDs must use the backend native identifier format')
        self.run = None

    def guard(self):
        if self.ui.adb('emu', 'avd', 'name').decode().splitlines()[0] != AVD:
            raise SafeFailure('Only the dedicated stridehub_native AVD is permitted')
        package = self.ui.adb('shell', 'dumpsys', 'package', PACKAGE).decode(errors='replace')
        if 'DEBUGGABLE' not in package or not re.search(r'\bversionCode='+str(self.args.version_code)+r'\s', package):
            raise SafeFailure('Expected dedicated debug APK/version was not installed')
        marker = self.api.request('/__e2e_health.json', authenticated=False)
        if not (marker.get('kind') == 'stridehub-native-e2e'
                and marker.get('instanceId') == self.credentials['instanceId']
                and marker.get('productionData') is False and marker.get('allowNativeWrites') is True):
            raise SafeFailure('Public disposable-instance marker did not match; refusing login or UI writes')
        session = self.api.request('/mobile/v1/sessions', 'POST', {
            'username': self.credentials['username'], 'password': self.credentials['password'],
            'deviceName': 'DEMO public NAS acceptance observer'}, authenticated=False)
        self.api.token = session.get('token', '')
        if not self.api.token or session.get('apiVersion') != 1 or 'mobile-workflows' not in session.get('capabilities', []):
            raise SafeFailure('Mobile session capability handshake failed')
        context = self.api.request('/api/v1/health/context')
        marker_text = 'STRIDEHUB_NATIVE_E2E:'+self.credentials['instanceId']
        if marker_text not in context.get('notes', ''):
            raise SafeFailure('Authenticated fixture marker did not match; refusing UI writes')
        profile = self.api.request('/api/v1/training/profile')
        if profile.get('timezone') != self.fixtures.get('timezone', 'Asia/Shanghai'):
            raise SafeFailure('Fixture training profile timezone is not the expected synthetic profile')
        for path in ['/api/v1/health/plans/'+self.plan_id, '/api/v1/training/sessions/'+self.session_id]:
            record = self.api.request(path)
            if not record.get('title', '').startswith('DEMO'):
                raise SafeFailure('Required mutable plan/session fixture is absent or not DEMO-labelled')
        sync = self.api.request('/api/v1/mobile/sync')
        if any(sync.get('sources', {}).get(kind, {}).get('state') != 'not_configured' for kind in ('daily', 'sleep', 'workouts')):
            raise SafeFailure('Fixture must not contain real Zepp credentials')
        self.identity_verified = True
        self.report['guards'] = {'validHttps': True, 'exactFixturePath': True, 'matchingPublicInstanceId': True,
                                 'matchingAuthenticatedMarker': True, 'syntheticProfile': True, 'zeppUnconfigured': True}

    def ensure_ui_identity(self):
        self.ui.tap('设置与更多')
        self.ui.wait('连接与账号')
        visible = self.ui.texts()
        if self.base not in visible or self.credentials['username'] not in visible:
            raise SafeFailure('Current app destination/account is not this disposable instance; no UI action taken')
        self.ui_identity_verified = True
        self.ui.tap('返回')

    def login(self):
        self.ui.adb('shell', 'am', 'start', '-n', PACKAGE+'/io.stridehub.app.MainActivity')
        time.sleep(1)
        if self.ui.visible('连接并登录') or self.ui.visible('服务器地址') or self.ui.visible('连接 StrideHub'):
            # Reproduce the user's copied web URL; native normalization must remove /admin/app.
            self.ui.fill('服务器地址', self.base+'/admin/app')
            self.ui.fill('用户名', self.credentials['username'])
            self.ui.fill('密码', self.credentials['password']+'-deliberately-invalid')
            self.ui.checkbox('明确允许此连接使用 HTTP（局域网）',False)
            self.ui.checkbox('保持登录，最多七天',True)
            self.ui.tap('连接并登录', scroll=True)
            self.ui.wait('用户名或密码错误',timeout=self.args.http_timeout+20,partial=True)
            self.ui.fill('密码', self.credentials['password'])
            self.ui.tap('连接并登录', scroll=True)
            self.report['loginInput']={'copiedAdminAppAddress':True,'friendlyInvalidPasswordShown':True,
                                       'exactFieldValuesVerified':True,'passwordRemasked':True}
        else:
            # Resume from any native detail without logging out or changing its account.
            # The destination/account is still checked in settings before business actions.
            self.ui.tab('总览')
        self.ui.wait('最近的真实记录')
        self.ensure_ui_identity()
        self.ui.screenshot('login-public-https')

    def navigation(self):
        for label, expected in [('总览','最近的真实记录'), ('身体','身体与恢复'), ('运动','每一次运动'), ('计划','计划与日常')]:
            self.ui.tab(label)
            self.ui.wait(expected)
            self.ui.screenshot('tab-'+{'总览':'overview','身体':'body','运动':'exercise','计划':'plans'}[label])

    def meal(self):
        label = 'DEMO NAS meal '+self.run_id
        self.ui.tab('计划'); self.ui.tap('饮食记录', scroll=True)
        self.ui.wait('新增饮食记录'); self.ui.tap('新增饮食记录')
        self.ui.tap('早餐'); self.ui.fill('食物 *',label); self.ui.fill('份量','1 bowl')
        self.ui.tap('保存饮食记录',scroll=True); self.ui.wait(label)
        record = next((row for row in self.api.request('/api/v1/health/meals').get('records',[]) if row.get('foods') == label),None)
        if not record or record.get('version') != 1 or record.get('portion') != '1 bowl':
            raise SafeFailure('Native meal create did not read back the expected committed record')
        self.ui.tap(label); self.ui.fill('食物 *',label+' updated')
        self.ui.tap('保存饮食记录',scroll=True); self.ui.wait(label+' updated')
        changed = self.api.request('/api/v1/health/meals/'+record['id'])
        if changed.get('version') != 2 or changed.get('foods') != label+' updated':
            raise SafeFailure('Native meal update did not read back the expected next version')
        self.ui.screenshot('meal-roundtrip')
        self.ui.tap(label+' updated'); self.ui.tap('删除此记录',scroll=True); self.ui.tap('删除')
        self.ui.wait('新增饮食记录')
        if any(row['id'] == record['id'] for row in self.api.request('/api/v1/health/meals').get('records',[])):
            raise SafeFailure('Native meal delete was not reflected by the public API')

    def plans(self):
        plan = self.api.request('/api/v1/health/plans/'+self.plan_id)
        self.ui.tab('计划'); self.ui.tap('健康计划',scroll=True); self.ui.tap(plan['title'])
        self.ui.fill('目标 *','DEMO NAS plan '+self.run_id)
        self.ui.tap('保存健康计划',scroll=True); self.ui.wait(plan['title'])
        changed = self.api.request('/api/v1/health/plans/'+self.plan_id)
        if changed.get('version') != plan['version']+1 or changed.get('goal') != 'DEMO NAS plan '+self.run_id or changed.get('items') != plan.get('items'):
            raise SafeFailure('Native health plan update failed version/content preservation checks')
        self.ui.tab('计划'); self.ui.tap('训练课表',scroll=True)
        session = self.api.request('/api/v1/training/sessions/'+self.session_id)
        self.ui.tap(session['title']); self.ui.fill('课表名称 *','DEMO NAS training '+self.run_id)
        self.ui.tap('执行反馈',scroll=True)
        root = self.ui.tree(); parents = {child:parent for parent in root.iter('node') for child in parent}
        node = next((node for node in root.iter('node') if node.get('text') == '执行反馈'),None)
        while node is not None:
            switches = [child for child in node.iter('node') if child.get('checkable') == 'true']
            if switches:
                if switches[0].get('checked') != 'true':
                    self.ui.adb('shell','input','tap',*self.ui.legacy.point(switches[0]))
                break
            node = parents.get(node)
        else:
            raise SafeFailure('Native training feedback switch was not found')
        self.ui.fill('主观强度 RPE (1–10)','4')
        self.ui.tap('保存训练课表',scroll=True); self.ui.wait('DEMO NAS training '+self.run_id)
        changed = self.api.request('/api/v1/training/sessions/'+self.session_id)
        if changed.get('version') != session['version']+1 or changed.get('feedback',{}).get('rpe') != 4:
            raise SafeFailure('Native training feedback failed public API readback')
        self.ui.screenshot('plan-feedback')

    def running(self):
        overview = self.api.request('/api/v1/running/overview?from=all')
        activities = overview.get('activities',{}).get('items',[])
        target_id = self.fixtures.get('runActivityId')
        self.run = next((row for row in activities if row['id'] == target_id),None) if target_id else next((row for row in activities if row.get('name','').startswith('DEMO')),None)
        if self.run is None:
            raise SafeFailure('A real imported DEMO run fixture is required for native analysis')
        detail = self.api.request('/api/v1/running/activities/'+urllib.parse.quote(self.run['id'],safe=''))
        analysis = detail.get('analysis',{})
        if not analysis.get('timeline') or not analysis.get('scatter') or not analysis.get('zones') or not detail.get('splits'):
            raise SafeFailure('Imported run fixture lacks the required measured analysis outputs')
        self.ui.tab('运动'); self.ui.tap('跑步分析'); self.ui.scroll_to_top('跑步复盘'); self.ui.wait('跑步复盘')
        self.ui.tap('全部历史')
        self.ui.wait('周跑量 · km'); self.ui.screenshot('running-weekly')
        self.ui.tap('跑步活动日历',scroll=True); self.ui.screenshot('running-calendar')
        self.ui.tap('已计算的最佳片段',scroll=True); self.ui.screenshot('running-best-efforts')
        self.ui.tap(self.run['name'],scroll=True); self.ui.scroll_to_top(self.run['name']); self.ui.wait(self.run['name'])
        self.capture_running_sections()
        self.report['running'] = {'realImportedFixture': True, 'timelineSamples':len(analysis.get('timeline',[])),
                                  'scatterPoints':len(analysis.get('scatter',[])), 'zones':len(analysis.get('zones',[])),
                                  'splits':len(detail.get('splits',[])), 'containsHealthPayload':False}

    def capture_running_sections(self):
        for label, filename in [('配速与心率','running-scatter'),('训练心率区间 · 分钟','running-zones'),('心率分布 · 分钟','running-distribution'),('数据覆盖与记录缺口','running-coverage'),('公里分段与 GAP','running-splits')]:
            self.ui.last_stage=filename
            self.ui.show_section(label)
            self.ui.screenshot(filename)

    def dates(self):
        self.ui.tab('运动'); self.ui.tap('跑步分析'); self.ui.scroll_to_top('跑步复盘'); self.ui.wait('跑步复盘')
        starts = [text for text in self.ui.texts() if re.fullmatch(r'起 \d{4}-\d{2}-\d{2}',text)]
        if not starts:
            raise SafeFailure('Native running range selector could not be inspected')
        old = starts[0].split()[1]
        chosen_day = 2 if old.endswith('-01') else 1
        expected = old[:-2]+f'{chosen_day:02}'
        self.ui.tap(starts[0]); self.ui.tap(str(chosen_day))
        self.ui.tap_any(['确定','OK','Ok'])
        self.ui.wait('起 '+expected)
        self.ui.wait('周跑量 · km'); self.ui.screenshot('native-date-range')
        self.report['dateRange'] = {'nativePickerChangedRange':True}

    def draft(self):
        label = 'DEMO NAS unsaved '+self.run_id
        before = len(self.api.request('/api/v1/health/meals').get('records',[]))
        self.ui.tab('计划'); self.ui.tap('饮食记录',scroll=True); self.ui.tap('新增饮食记录')
        self.ui.fill('食物 *',label); self.ui.fill('份量','unsaved fixture')
        time.sleep(.8)
        old_pid = self.ui.adb('shell','pidof',PACKAGE).decode().strip()
        self.ui.adb('shell','input','keyevent','KEYCODE_HOME'); time.sleep(1)
        self.ui.adb('shell','am','kill',PACKAGE); time.sleep(.7)
        self.ui.adb('shell','am','start','-n',PACKAGE+'/io.stridehub.app.MainActivity')
        self.ui.wait(label)
        new_pid = self.ui.adb('shell','pidof',PACKAGE).decode().strip()
        if not old_pid or not new_pid or old_pid == new_pid:
            raise SafeFailure('Draft scenario did not establish an actual process replacement')
        if len(self.api.request('/api/v1/health/meals').get('records',[])) != before:
            raise SafeFailure('An unsaved native draft unexpectedly created a server record')
        self.ui.screenshot('draft-process-restored')
        self.ui.tap('返回'); self.ui.wait('新增饮食记录')

    def collection_records(self):
        rows=[];offset=0
        for _ in range(20):
            page=self.api.request('/api/v1/health/collection/weight?offset='+str(offset)+'&limit=100')
            current=page.get('records',[]);rows.extend(current);offset+=len(current)
            if offset>=page.get('total',0):return rows
            if not current:break
        raise SafeFailure('Disposable collection pagination exceeded its bounded test size')

    def weighing(self):
        # Pairing UI can enqueue history by design. Never press it unless the history AND queue
        # are provably empty; then create just one manual fixture while Android is offline.
        self.ui.last_stage='weighing-empty-history-pair'
        self.ui.tab('总览');self.ui.tap('开始称重');self.ui.wait('身体记录')
        require_empty_pairing_history(self.ui.local_state())
        before_collection=self.collection_records()
        before_daily=self.api.request('/api/v1/health/weight')
        self.ui.tap('设置');self.ui.scroll_to_top('NAS 同步');self.ui.wait('NAS 同步')
        require_empty_pairing_history(self.ui.local_state())
        self.ui.tap('配对 NAS 并上传本机历史',scroll=True)
        self.ui.wait('上传到 NAS？')
        if not any(self.base in text for text in self.ui.texts()):
            raise SafeFailure('Native weighing confirmation does not name the guarded public destination')
        # Recheck after the dialog, immediately before the sole pairing action.
        require_empty_pairing_history(self.ui.local_state())
        self.ui.tap('确认配对并上传')
        self.ui.wait('NAS '+self.base,timeout=self.args.http_timeout+20,partial=True)
        require_empty_pairing_history(self.ui.local_state())
        if len(self.collection_records())!=len(before_collection):
            raise SafeFailure('Pairing an empty history unexpectedly created a server measurement')
        self.ui.screenshot('weighing-native-paired-empty-history')
        fixture_weight=71.4
        measurement=None;frozen=None;binding=None
        self.ui.last_stage='weighing-offline-single-manual'
        with android_offline(self.ui):
            self.ui.tap('称重');self.ui.tap('手工记录',scroll=True)
            self.ui.fill('体重（kg）',str(fixture_weight));self.ui.tap('保存到本机')
            self.ui.wait('已保存',partial=True)
            local=self.ui.local_state();measurement=require_single_manual_measurement(local,fixture_weight)
            queue=[row for row in local['queue'] if row['measurement_id']==measurement['id']]
            if len(queue)!=1 or queue[0]['uuid']!=measurement['upload_uuid'] or queue[0]['server']!=self.base:
                raise SafeFailure('The one manual record did not enter only its guarded public queue')
            frozen=queue[0]['payload'];binding=queue[0]['binding']
            self.ui.tap('设置');self.ui.scroll_to_top('NAS 同步');self.ui.wait('NAS 同步')
            for _ in range(2):self.ui.tap('同步 / 网络恢复后重试');time.sleep(.5)
            deadline=time.monotonic()+45
            while time.monotonic()<deadline:
                local=self.ui.local_state()
                queue=[row for row in local['queue'] if row['measurement_id']==measurement['id']]
                if len(queue)==1 and queue[0]['status']=='pending':break
                time.sleep(.4)
            else:raise SafeFailure('Offline weighing did not remain pending for safe retry')
            if queue[0]['payload']!=frozen or queue[0]['uuid']!=measurement['upload_uuid']:
                raise SafeFailure('Offline retry changed the frozen payload or stable UUID')
            if any(row.get('measurementId')==measurement['upload_uuid'] for row in self.collection_records()):
                raise SafeFailure('Android was not truly offline during the weighing retry')
            self.ui.screenshot('weighing-offline-single-pending-record')
        self.ui.last_stage='weighing-public-device-receipt'
        self.ui.tap('同步 / 网络恢复后重试')
        deadline=time.monotonic()+self.args.weighing_timeout
        acknowledged=None
        while time.monotonic()<deadline:
            local=self.ui.local_state();require_single_manual_measurement(local,fixture_weight)
            queue=[row for row in local['queue'] if row['measurement_id']==measurement['id']]
            records=self.collection_records()
            if len(queue)==1 and queue[0]['status'] in ('synced','manual_conflict'):
                acknowledged=require_unique_server_capture(records,measurement['upload_uuid'],fixture_weight,binding)
                break
            time.sleep(1)
        if acknowledged is None:raise SafeFailure('Public weighing upload did not receive a confirmed device receipt')
        if queue[0]['payload']!=frozen or len(records)!=len(before_collection)+1:
            raise SafeFailure('Public weighing acknowledgement changed a payload or created extra captures')
        self.ui.last_stage='weighing-deduplication-projection'
        # Repeated user retry must not create another capture for this already acknowledged UUID.
        self.ui.tap('同步 / 网络恢复后重试');time.sleep(2)
        repeated=self.collection_records()
        require_unique_server_capture(repeated,measurement['upload_uuid'],fixture_weight,binding)
        if len(repeated)!=len(records):raise SafeFailure('Post-acknowledgement retry duplicated the fixture capture')
        local_date=measurement['local_day']
        daily=self.api.request('/api/v1/health/weight')
        baseline=next((row for row in before_daily.get('records',[]) if row.get('date')==local_date),None)
        projected=next((row for row in daily.get('records',[]) if row.get('date')==local_date),None)
        conflict=queue[0]['status']=='manual_conflict'
        if self.fixtures.get('expectWeighingConflict') is True and not conflict:
            raise SafeFailure('The requested manual-conflict fixture did not produce its expected protected conflict')
        if conflict:
            if baseline is None or projected!=baseline:
                raise SafeFailure('The existing manual daily weight was not preserved during the collection conflict')
            self.ui.wait('NAS 日记录冲突',partial=True)
            self.ui.screenshot('weighing-manual-conflict-shown')
        else:
            if projected is None or not exact_weight(projected.get('weightKg'),fixture_weight):
                raise SafeFailure('Confirmed collection did not produce the exact expected daily weight projection')
            self.ui.wait('NAS 已确认',partial=True)
            self.ui.screenshot('weighing-public-acknowledged')
        read_model=self.api.request('/api/v1/health/collected/weight?from='+local_date+'&to='+local_date+'&limit=100')
        if sum(row.get('measurementId')==measurement['upload_uuid'] for row in read_model.get('records',[]))!=1:
            raise SafeFailure('Public collected-weight read model lost or duplicated the exact captured UUID')
        self.report['weighing']={
            'manualEntryNotBle':True,'historyAndQueueEmptyBeforePair':True,'pairedThroughNativeUi':True,
            'newLocalRows':1,'exactUuidAndWeightReadBack':True,'offlinePendingRetries':2,
            'frozenPayloadPreserved':True,'postAckRetryDidNotDuplicate':True,'serverNewCaptures':1,
            'manualConflictShown':conflict,'serverManualValuePreserved':conflict,
            'lostReceiptInjectionTested':False,
            'measurementIdentitySha256':hashlib.sha256(measurement['upload_uuid'].encode()).hexdigest()}
        self.ui.tap('返回 StrideHub');self.ui.wait('最近的真实记录')

    def offline(self):
        self.ui.tab('总览'); self.ui.wait('最近的真实记录')
        local_before = None; local_after = None
        with android_offline(self.ui):
            self.ui.tap('刷新'); self.ui.wait('离线缓存',timeout=self.args.offline_timeout,partial=True)
            self.ui.screenshot('offline-timestamped-cache')
            self.ui.tap('开始称重'); self.ui.wait('身体记录')
            local_before = self.ui.local_snapshot()
            reused_weighing = bool(self.report.get('weighing'))
            if reused_weighing:
                if len(local_before)!=1:raise SafeFailure('The single earlier manual fixture was not retained for offline access')
                self.ui.tap('记录');self.ui.wait('71.4 kg')
                local_after = self.ui.local_snapshot()
                if local_after!=local_before:raise SafeFailure('Reading offline weighing history unexpectedly changed the saved row')
            else:
                self.ui.tap('手工记录',scroll=True); self.ui.fill('体重（kg）','71.3')
                self.ui.tap('保存到本机'); self.ui.wait('已保存',partial=True)
                local_after = self.ui.local_snapshot()
                new = [row for row in local_after if row[0] not in {row[0] for row in local_before}]
                if len(new) != 1 or abs(new[0][1]-71.3) > .001 or not new[0][2]:
                    raise SafeFailure('Offline native weighing did not create exactly one local record with stable identity')
            self.ui.screenshot('offline-weighing-saved')
            self.ui.tap('返回 StrideHub'); self.ui.wait('最近的真实记录')
        self.ui.tap('刷新')
        until = time.monotonic()+self.args.offline_timeout
        while time.monotonic() < until:
            visible = self.ui.texts()
            if '最近的真实记录' in visible and not any('离线缓存' in text or '缓存预览，正在核对' in text for text in visible):
                self.ui.screenshot('public-reconnected'); break
            time.sleep(.4)
        else:
            raise SafeFailure('Restored Android networking did not yield a freshly confirmed public response')
        self.report['offline'] = {'networkRestored':True,'timestampedCache':True,'localWeighingCommitted':not reused_weighing,'priorManualFixtureReadable':reused_weighing,
                                  'newLocalRows':len(local_after)-len(local_before),'weighingUploadClaimed':False}

    def sync(self):
        before = self.api.request('/api/v1/mobile/sync')
        prior = {row['id'] for row in before.get('records',[])}
        self.ui.tab('总览'); self.ui.tap('设置与更多'); self.ui.tap('Zepp 同步与任务状态')
        self.ui.wait('同步每日健康'); self.ui.tap('同步每日健康')
        until = time.monotonic()+self.args.job_timeout
        job = None
        while time.monotonic() < until:
            records = self.api.request('/api/v1/mobile/sync').get('records',[])
            candidate = next((row for row in records if row['id'] not in prior and row.get('kind') == 'daily'),None)
            if candidate is None:
                candidate = next((row for row in records if row.get('kind') == 'daily' and row.get('state') in ('queued','running')),None)
            if candidate:
                job = candidate
                if job.get('state') not in ('queued','running'):
                    break
            time.sleep(2)
        if not job or job.get('state') != 'failed' or (job.get('result') or {}).get('state') != 'not_configured':
            raise SafeFailure('Public sync job did not reach truthful failed/not_configured via the NAS worker')
        self.ui.tap('刷新'); self.ui.tap('任务记录',scroll=True)
        self.ui.wait('每日健康 · 失败'); self.ui.screenshot('public-sync-final-state')
        self.report['sync'] = {'serverWorkerReachedTerminalState':True,'reportedUnconfiguredHonestly':True}

    def logout(self):
        self.ui.tab('总览'); self.ui.tap('设置与更多')
        self.ui.tap('退出 / 切换服务器或账号',scroll=True); self.ui.wait('连接 StrideHub')
        self.ui.tap('离线称重与本机历史',scroll=True); self.ui.wait('身体记录')
        self.ui.screenshot('logged-out-offline-weighing'); self.ui.tap('返回 StrideHub')
        self.ui.wait('连接 StrideHub')

    def run_step(self, name, action):
        self.ui.last_stage = name
        started = time.monotonic()
        entry = {'name':name,'status':'running'}
        self.report['checks'].append(entry)
        write_private_json(self.out/'report.json',self.report)
        try:
            action()
        except Exception:
            entry.update(status='failed',seconds=round(time.monotonic()-started,3))
            raise
        entry.update(status='passed',seconds=round(time.monotonic()-started,3))
        write_private_json(self.out/'report.json',self.report)
        print('PASS',name,flush=True)

    def execute(self):
        try:
            self.run_step('fixture-guards',self.guard)
            self.run_step('native-https-login',self.login)
            selected = SCENARIOS if self.args.scenarios == 'all' else tuple(self.args.scenarios.split(','))
            if any(name not in SCENARIOS for name in selected):
                raise SafeFailure('Unknown requested scenario')
            for name in selected:
                self.run_step(name,getattr(self,name))
            self.report['status'] = 'passed'
            return 0
        except Exception as error:
            self.report['status'] = 'failed'
            self.report['failedAt'] = self.ui.last_stage
            self.report['failure'] = str(error) if isinstance(error,SafeFailure) else 'Unexpected test/harness failure; raw exception omitted'
            if self.identity_verified and self.ui_identity_verified:
                try:
                    self.ui.screenshot('FAILED-'+self.ui.last_stage)
                except Exception:
                    pass
            print('FAIL',self.ui.last_stage,':',self.report['failure'],flush=True)
            return 1
        finally:
            if self.api.token:
                try:
                    self.api.request('/mobile/v1/session','DELETE')
                    self.report['observerSessionRevoked'] = True
                except Exception:
                    self.report['observerSessionRevoked'] = False
                self.api.token = ''
            write_private_json(self.out/'report.json',self.report)
            print('Report:',self.out/'report.json',flush=True)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',required=True,help='Public HTTPS installation root with exact disposable-instance prefix')
    parser.add_argument('--credentials',required=True,type=Path,help='Private JSON: username/password/instanceId')
    parser.add_argument('--fixtures',type=Path,help='Optional private JSON: planId/sessionId/runActivityId/timezone/expectWeighingConflict')
    parser.add_argument('--report-dir',required=True,type=Path)
    parser.add_argument('--adb',type=Path,default=Path(os.environ.get('ANDROID_HOME',''))/'platform-tools/adb')
    parser.add_argument('--version-code',type=int,default=5)
    parser.add_argument('--scenarios',default='all',help='all or comma-separated '+','.join(SCENARIOS))
    parser.add_argument('--http-timeout',type=int,default=45)
    parser.add_argument('--offline-timeout',type=int,default=75)
    parser.add_argument('--job-timeout',type=int,default=240)
    parser.add_argument('--weighing-timeout',type=int,default=120)
    return parser.parse_args(argv)


def main():
    try:
        acceptance = NasAcceptance(parse_args())
        return acceptance.execute()
    except Exception as error:
        print('FAIL setup:',str(error) if isinstance(error,SafeFailure) else 'Invalid private configuration; details omitted',flush=True)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
