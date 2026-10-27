#!/usr/bin/env python3
"""Owned public-fixture persistent-session and signed Android upgrade acceptance.

No service administration, APK installation, data clearing, database writes or device startup.
Execute one explicit phase at a time. Reports omit credentials and health content.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

SOURCE=Path(__file__).with_name('native-android-nas.py')
spec=importlib.util.spec_from_file_location('stridehub_persistent_nas_helpers',SOURCE)
nas=importlib.util.module_from_spec(spec);sys.modules[spec.name]=nas;spec.loader.exec_module(nas)
Failure=nas.SafeFailure
CAPABILITY='persistent-mobile-sessions'
TABLES=('measurements','measurement_derivations','legacy_imports','sync_queue')
PHASES=('api-begin','api-after-restart','api-finish','device-before','device-after','device-restart','device-logout')


def require(condition,message):
    if not condition:raise Failure(message)


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()


def persistent_metadata(data):
    require(data.get('apiVersion')==1 and CAPABILITY in data.get('capabilities',[])
            and data.get('persistent') is True and 'expiresAt' in data and data['expiresAt'] is None,
            'Expected explicit persistent:true, expiresAt:null and persistent-session capability')


def legacy_metadata(data):
    require(data.get('persistent') in (False,None) and isinstance(data.get('expiresAt'),str),
            'Legacy request must retain a finite session expiry')
    try:remaining=(datetime.fromisoformat(data['expiresAt'].replace('Z','+00:00'))-datetime.now(timezone.utc)).total_seconds()
    except (ValueError,TypeError):raise Failure('Legacy session expiry was not a valid timestamp') from None
    require(abs(remaining-604800)<180,'Default legacy login must still expire after seven days')


def preference_values(raw):
    try:
        root=ET.fromstring(raw)
        return {item.get('name'):item.text if item.tag=='string' else item.get('value') for item in root}
    except (ValueError,ET.ParseError):raise Failure('Could not parse owned application preferences') from None


def require_connection(values,base,username):
    require(values.get('server')==base and values.get('username')==username,
            'Owned app destination/account is not the guarded fixture; no device action permitted')


def require_persistent_preferences(values):
    require(values.get('persistent')=='true' and values.get('expiresAt') in (None,'','null'),
            'App has not persisted explicit permanent-session metadata')
    require(bool(values.get('token')) and not values['token'].startswith('shmobile_'),
            'App session must remain encrypted in Android Keystore-backed storage')


class Api(nas.HttpsApi):
    def exchange(self,path,method='GET',body=None,token=None,expected=200):
        headers={'Accept':'application/json','Cache-Control':'no-store'}
        if token:headers['Authorization']='Bearer '+token
        raw=None
        if body is not None:headers['Content-Type']='application/json';raw=json.dumps(body).encode()
        request=urllib.request.Request(self.base+path,method=method,headers=headers,data=raw)
        try:
            try:response=self.opener.open(request,timeout=self.timeout)
            except urllib.error.HTTPError as error:response=error
            with response:
                payload=response.read(16*1024*1024+1);status=response.code
                self.events.append({'method':method,'path':path,'status':status})
                require(len(payload)<=16*1024*1024,'Fixture response exceeds bounded size')
                require(status==expected,'Unexpected fixture HTTP status '+str(status)+'; expected '+str(expected))
                require(not payload or 'application/json' in response.headers.get('Content-Type',''),'Expected fixture JSON response')
                return json.loads(payload) if payload else {}
        except Failure:raise
        except Exception:raise Failure('Verified public HTTPS exchange failed; sensitive output omitted') from None


class Acceptance:
    def __init__(self,args):
        self.args=args;self.base=nas.validate_base(args.base)
        self.credentials=json.loads(args.credentials.read_text())
        require(all(isinstance(self.credentials.get(k),str) and self.credentials[k] for k in ('username','password','instanceId')),
                'Private credentials require username/password/instanceId')
        self.out=args.output.resolve();self.out.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.api=Api(self.base,args.timeout);self.observer='';self.created_tokens=[];self.retained_tokens=set()
        self.report={'phase':args.phase,'base':self.base,'instanceId':self.credentials['instanceId'],
                     'checks':[],'apiEvents':self.api.events,'containsCredentials':False,
                     'scope':'owned disposable public fixture and dedicated emulator only'}
        self.package='io.stridehub.app'+('.debug' if args.package=='debug' else '')
        nas.PACKAGE=self.package
        self.ui=nas.NativeUi(args.adb,self.out)

    def passed(self,name):
        self.report['checks'].append(name);print('PASS '+name,flush=True)

    def login(self,persistent=None):
        body={'username':self.credentials['username'],'password':self.credentials['password'],
              'deviceName':'DEMO persistent acceptance observer'}
        if persistent is not None:body['persistent']=persistent
        session=self.api.exchange('/mobile/v1/sessions','POST',body,expected=201)
        require(isinstance(session.get('token'),str) and re.fullmatch(r'shmobile_[a-f0-9]{64}',session['token']) is not None,
                'Fixture returned an invalid mobile token envelope')
        self.created_tokens.append(session['token'])
        return session

    def guard_api(self):
        marker=self.api.exchange('/__e2e_health.json')
        require(marker.get('kind')=='stridehub-native-e2e' and marker.get('instanceId')==self.credentials['instanceId']
                and marker.get('productionData') is False and marker.get('allowNativeWrites') is True,
                'Public disposable marker mismatch; no authenticated/device action permitted')
        session=self.login();self.observer=session['token']
        context=self.api.exchange('/api/v1/health/context',token=self.observer)
        require('STRIDEHUB_NATIVE_E2E:'+self.credentials['instanceId'] in context.get('notes',''),
                'Authenticated disposable marker mismatch')
        self.passed('public-and-authenticated-fixture-markers')

    def state_path(self):return self.out/'api-state.private.json'

    def state(self):
        path=self.state_path()
        require(path.exists() and path.stat().st_mode & 0o077==0,'API checkpoint must exist with private permissions')
        data=json.loads(path.read_text())
        require(data.get('base')==self.base and data.get('instanceId')==self.credentials['instanceId'],
                'API checkpoint belongs to another destination/instance')
        return data

    def api_begin(self):
        require(not self.state_path().exists(),'API checkpoint already exists; refusing to replace live test credentials')
        legacy=self.login();legacy_metadata(legacy)
        permanent=self.login(True);persistent_metadata(permanent)
        old=legacy['token'];new=permanent['token']
        for _ in range(2):persistent_metadata(self.api.exchange('/mobile/v1/session','PATCH',{'persistent':True},old))
        persistent_metadata(self.api.exchange('/mobile/v1/session',token=old))
        self.passed('legacy-default-seven-days-and-idempotent-same-token-upgrade')
        self.passed('new-persistent-login-explicit-null-expiry')
        revoked=self.login(True)['token']
        self.api.exchange('/mobile/v1/session','DELETE',token=revoked,expected=204)
        self.api.exchange('/mobile/v1/session',token=revoked,expected=401)
        self.api.exchange('/mobile/v1/session','PATCH',{'persistent':True},revoked,401)
        self.passed('revoked-token-cannot-be-upgraded-or-revived')
        nas.write_private_json(self.state_path(),{'base':self.base,'instanceId':self.credentials['instanceId'],
            'legacyToken':old,'persistentToken':new,'createdAt':datetime.now(timezone.utc).isoformat()})
        self.retained_tokens.update((old,new))
        self.report['checkpointContainsTestTokens']=True
        self.report['nextPhase']='Root restarts only the owned NAS stage, then runs api-after-restart'

    def api_after_restart(self):
        state=self.state()
        for key in ('legacyToken','persistentToken'):
            persistent_metadata(self.api.exchange('/mobile/v1/session',token=state[key]))
            persistent_metadata(self.api.exchange('/mobile/v1/session','PATCH',{'persistent':True},state[key]))
        self.passed('same-two-tokens-remain-persistent-and-usable')
        self.report['restartPerformedByScript']=False
        self.report['externalRestartEvidenceRequired']=True

    def api_finish(self):
        state=self.state()
        for key in ('legacyToken','persistentToken'):
            self.api.exchange('/mobile/v1/session','DELETE',token=state[key],expected=204)
            self.api.exchange('/mobile/v1/session',token=state[key],expected=401)
            self.api.exchange('/mobile/v1/session','PATCH',{'persistent':True},state[key],401)
        fresh=self.login(True);persistent_metadata(fresh)
        self.api.exchange('/mobile/v1/session','DELETE',token=fresh['token'],expected=204)
        self.api.exchange('/mobile/v1/session',token=fresh['token'],expected=401)
        self.passed('persistent-logout-401-no-patch-resurrection-and-fresh-login')
        self.state_path().unlink()

    def private(self,relative,optional=False):
        require(relative in ('shared_prefs/connection.xml','shared_prefs/native_session.xml',
                            'databases/tinyyumi.db','databases/tinyyumi.db-wal'),'Unapproved private read')
        try:
            if self.args.package=='debug':return self.ui.adb('exec-out','run-as',self.package,'cat',relative)
            return self.ui.adb('exec-out','su','0','cat','/data/data/'+self.package+'/'+relative)
        except Failure:
            if optional:return b''
            raise Failure('Owned app private read failed; release needs read-only su0 on this dedicated emulator') from None

    def package_info(self):
        raw=self.ui.adb('shell','dumpsys','package',self.package).decode(errors='replace')
        version=re.search(r'\bversionCode=(\d+)\s',raw);uid=re.search(r'\b(?:userId|appId)=(\d+)',raw)
        require(version is not None and uid is not None,'Expected installed owned app package was not found')
        require(('DEBUGGABLE' in raw)==(self.args.package=='debug'),'Package build type does not match requested release/debug scope')
        return {'versionCode':int(version.group(1)),'uid':uid.group(1)}

    def guard_device(self):
        require(self.ui.adb('emu','avd','name').decode().splitlines()[0].strip()=='stridehub_native',
                'Refusing any device except dedicated emulator-5556 / stridehub_native')
        info=self.package_info();expected=5 if self.args.phase=='device-before' else 6
        require(info['versionCode']==expected,'Wrong owned APK versionCode; expected '+str(expected))
        connection=preference_values(self.private('shared_prefs/connection.xml'))
        require_connection(connection,self.base,self.credentials['username'])
        self.passed('owned-avd-package-version-and-private-fixture-connection')
        return info

    def signing_digest(self):
        require(self.args.apksigner is not None,'Device phases require --apksigner from Android build-tools')
        output=self.ui.adb('shell','pm','path',self.package).decode().strip().splitlines()
        path=next((line[len('package:'):] for line in output if line.endswith('/base.apk')),None)
        require(path is not None,'Installed base APK path was not found')
        with tempfile.TemporaryDirectory(prefix='stridehub-owned-apk-') as folder:
            apk=Path(folder)/'base.apk';apk.write_bytes(self.ui.adb('exec-out','cat',path))
            try:result=subprocess.check_output([str(self.args.apksigner),'verify','--print-certs',str(apk)],stderr=subprocess.STDOUT).decode()
            except Exception:raise Failure('Installed APK signature verification failed; raw output omitted') from None
            fingerprints=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)',result)
            require(len(fingerprints)==1,'Expected one verified APK signing certificate')
            return fingerprints[0].lower()

    def scale_snapshot(self):
        with tempfile.TemporaryDirectory(prefix='stridehub-owned-scale-') as directory:
            path=Path(directory)/'scale.db';path.write_bytes(self.private('databases/tinyyumi.db'))
            wal=self.private('databases/tinyyumi.db-wal',optional=True)
            if wal:Path(str(path)+'-wal').write_bytes(wal)
            try:
                db=sqlite3.connect(path)
                try:
                    require(db.execute('PRAGMA integrity_check').fetchone()[0]=='ok','Owned weighing database snapshot is inconsistent')
                    tables={table:db.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall() for table in TABLES}
                finally:db.close()
            except sqlite3.Error:raise Failure('Owned weighing snapshot could not be read consistently') from None
            return {'sha256':digest(tables),'counts':{name:len(rows) for name,rows in tables.items()}}

    def session_snapshot(self,persistent=False):
        values=preference_values(self.private('shared_prefs/native_session.xml'))
        require_connection(values,self.base,self.credentials['username'])
        require(bool(values.get('token')) and not values['token'].startswith('shmobile_'),'Expected encrypted remembered mobile session')
        require(not any('password' in str(key).lower() for key in values),'Remembered app session must never contain a password field')
        if persistent:require_persistent_preferences(values)
        else:
            require(isinstance(values.get('expiresAt'),str) and values.get('persistent')!='true',
                    'Upgrade baseline must be an actual finite 0.3.1 session')
        return {'cipherSha256':hashlib.sha256(values['token'].encode()).hexdigest(),'persistent':persistent}

    def baseline_path(self):return self.out/(self.args.package+'-before.json')

    def baseline(self):
        data=json.loads(self.baseline_path().read_text())
        require(data['base']==self.base and data['instanceId']==self.credentials['instanceId'] and data['package']==self.package,
                'Device checkpoint belongs to another destination/account/package')
        require(data['usernameSha256']==hashlib.sha256(self.credentials['username'].encode()).hexdigest(),'Device checkpoint account mismatch')
        return data

    def verify_data(self,baseline):
        current=self.scale_snapshot()
        require(current==baseline['scale'],'Upgrade/restart/logout changed saved weighing rows, UUIDs, derivations, imports or queue')
        return current

    def signed_in_ui(self):
        self.ui.adb('shell','am','start','-n',self.package+'/io.stridehub.app.MainActivity')
        time.sleep(1)
        require(not self.ui.visible('连接并登录'),'App unexpectedly requires login')
        self.ui.tab('总览');self.ui.wait('最近的真实记录')
        self.ui.tap('刷新');self.ui.wait('最近的真实记录')
        deadline=time.monotonic()+self.args.timeout
        while True:
            visible=self.ui.texts()
            if not any('离线缓存' in value or '缓存预览，正在核对' in value for value in visible):break
            require(time.monotonic()<deadline,'App refresh remained offline or unconfirmed')
            time.sleep(.4)
        self.ui.tap('设置与更多');self.ui.wait('连接与账号')
        texts=self.ui.texts()
        require(self.base in texts and self.credentials['username'] in texts,'Native UI does not show exact fixture destination/account')
        self.ui.tap('返回')

    def device_before(self):
        require(not self.baseline_path().exists(),'Upgrade baseline exists; refusing to overwrite preservation evidence')
        info=self.guard_device();signature=self.signing_digest()
        self.signed_in_ui()
        self.ui.adb('shell','am','force-stop',self.package)
        scale=self.scale_snapshot();require(scale['counts']['measurements']>=self.args.min_local_rows,'Create the owned release manual fixture before freezing the upgrade baseline')
        session=self.session_snapshot()
        nas.write_private_json(self.baseline_path(),{'base':self.base,'instanceId':self.credentials['instanceId'],
            'package':self.package,'usernameSha256':hashlib.sha256(self.credentials['username'].encode()).hexdigest(),
            'packageInfo':info,'signingSha256':signature,'scale':scale,'session':session})
        self.passed('actual-031-signed-in-encrypted-session-and-local-weighing-baseline')

    def device_after(self):
        info=self.guard_device();before=self.baseline()
        require(info['uid']==before['packageInfo']['uid'],'Cover installation changed the app UID')
        require(self.signing_digest()==before['signingSha256'],'Cover installation changed the app signing certificate')
        self.ui.adb('shell','am','force-stop',self.package)
        self.verify_data(before)
        self.signed_in_ui()
        deadline=time.monotonic()+self.args.timeout
        while True:
            try:session=self.session_snapshot(True);break
            except Failure:
                if time.monotonic()>=deadline:raise
                time.sleep(1)
        require(session['cipherSha256']==before['session']['cipherSha256'],'Old session token ciphertext changed during metadata-only upgrade')
        self.verify_data(before)
        self.ui.screenshot(self.args.package+'-032-upgraded-without-login')
        self.passed('same-signature-uid-token-cipher-and-all-scale-tables-after-031-to-032')
        self.device_restart(guarded=True)

    def device_restart(self,guarded=False):
        if not guarded:self.guard_device()
        before=self.baseline()
        for _ in range(self.args.restarts):
            self.ui.adb('shell','am','force-stop',self.package)
            self.signed_in_ui()
            require(self.session_snapshot(True)['cipherSha256']==before['session']['cipherSha256'],
                    'App process restart replaced the remembered original session')
            self.verify_data(before)
        self.ui.screenshot(self.args.package+'-force-stop-restored')
        self.passed('force-stop-relaunch-retains-persistent-identity-and-all-local-data')

    def device_logout(self):
        self.guard_device();before=self.baseline();self.signed_in_ui()
        self.ui.tap('设置与更多');self.ui.tap('退出 / 切换服务器或账号',scroll=True)
        self.ui.wait('连接并登录')
        self.ui.adb('shell','am','force-stop',self.package)
        self.ui.adb('shell','am','start','-n',self.package+'/io.stridehub.app.MainActivity')
        self.ui.wait('连接并登录')
        session=preference_values(self.private('shared_prefs/native_session.xml'))
        require(not session.get('token'),'Logout left a reusable local mobile credential')
        self.verify_data(before)
        self.ui.screenshot(self.args.package+'-logout-not-resurrected')
        self.passed('native-logout-and-process-restart-do-not-revive-identity-or-delete-weighing')
        self.ui.fill('服务器地址',self.base+'/admin/app')
        self.ui.fill('用户名',self.credentials['username'])
        self.ui.fill('密码',self.credentials['password'])
        self.ui.tap('连接并登录',scroll=True);self.ui.wait('最近的真实记录',timeout=self.args.timeout)
        self.signed_in_ui();self.session_snapshot(True);self.verify_data(before)
        self.ui.screenshot(self.args.package+'-fresh-persistent-login')
        self.passed('fresh-native-login-after-logout-is-persistent-and-preserves-weighing')
        self.report['deviceOriginalTokenServer401Observed']=False
        self.report['requiredSeparateEvidence']='Debug instrumentation or server-side original-session DELETE/401 evidence; observer token is not the app token'

    def run(self):
        try:
            self.guard_api()
            getattr(self,self.args.phase.replace('-','_'))()
            self.report['passed']=True
            return 0
        except Exception as error:
            self.report['passed']=False
            self.report['failure']=str(error) if isinstance(error,Failure) else 'Unexpected acceptance failure; sensitive diagnostics omitted'
            print('FAIL '+self.report['failure'],flush=True)
            return 1
        finally:
            for token in self.created_tokens:
                if token in self.retained_tokens:continue
                try:self.api.exchange('/mobile/v1/session','DELETE',token=token,expected=204)
                except Exception:pass  # Some scenario credentials were already revoked deliberately.
            nas.write_private_json(self.out/(self.args.package+'-'+self.args.phase+'-report.json'),self.report)


def parse_args(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=PHASES)
    parser.add_argument('--base',required=True)
    parser.add_argument('--credentials',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--package',choices=('release','debug'),default='release')
    parser.add_argument('--adb',type=Path,default=Path(os.environ.get('ANDROID_HOME',''))/'platform-tools/adb')
    parser.add_argument('--apksigner',type=Path)
    parser.add_argument('--timeout',type=int,default=60)
    parser.add_argument('--min-local-rows',type=int,default=1)
    parser.add_argument('--restarts',type=int,default=2)
    return parser.parse_args(argv)


if __name__=='__main__':sys.exit(Acceptance(parse_args()).run())
