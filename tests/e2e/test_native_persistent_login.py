"""No-device/no-network guard and contract tests for persistent acceptance."""
import importlib.util
import base64
import json
import xml.etree.ElementTree as ET
from pathlib import Path
import sys
import types
import unittest
from datetime import datetime,timedelta,timezone
from unittest.mock import patch

SOURCE=Path(__file__).with_name('native-persistent-login.py')
spec=importlib.util.spec_from_file_location('native_persistent_login_tested',SOURCE)
m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)


class PersistentAcceptanceTest(unittest.TestCase):
    def revocation_xml(self,pending=True,tomb=True,payload=None,kind='string',tomb_kind='boolean',tomb_value='true'):
        self.base='https://nas-fixture.example'+m.nas.PREFIX
        self.identity='a'*64
        if payload is None:payload={'server':self.base,'username':'demo','token':base64.b64encode(b'i'*12).decode()+':'+base64.b64encode(b'c'*40).decode()}
        root=ET.Element('map')
        if pending:ET.SubElement(root,kind,{'name':self.identity}).text=json.dumps(payload)
        if tomb:ET.SubElement(root,tomb_kind,{'name':'loggedout.'+self.identity,'value':tomb_value})
        ET.SubElement(root,'long',{'name':'sequence','value':'1'})
        if pending:ET.SubElement(root,'long',{'name':'order.'+self.identity,'value':'1'})
        return ET.tostring(root)

    def test_revocation_parser_proves_one_encrypted_intent_with_tombstone_without_returning_payload(self):
        raw=self.revocation_xml();state=m.revocation_state(raw,self.base,'demo')
        self.assertEqual({self.identity},set(state['pending']))
        self.assertEqual({self.identity},state['tombstones'])
        self.assertNotIn(self.base,repr(state));self.assertNotIn('demo',repr(state))
        self.assertEqual(64,len(state['pending'][self.identity]))
        drained=m.revocation_state(self.revocation_xml(pending=False),self.base,'demo')
        m.require_logout_progress(drained,self.identity,set(),drained=True)

    def test_revocation_parser_rejects_plaintext_wrong_destination_malformed_cipher_and_missing_tombstone(self):
        self.revocation_xml()
        for change in ({'token':'shmobile_plaintext'}, {'server':'https://production.invalid'},
                       {'username':'another'}, {'password':'must-not-be-stored'}, {'token':'invalid:base64'}, {'token':'a:b'}):
            payload={'server':self.base,'username':'demo','token':base64.b64encode(b'i'*12).decode()+':'+base64.b64encode(b'c'*40).decode()};payload.update(change)
            with self.subTest(change=tuple(change)),self.assertRaises(m.Failure):
                m.revocation_state(self.revocation_xml(payload=payload),self.base,'demo')
        for options in ({'tomb':False},{'kind':'boolean'},{'tomb_kind':'string'},{'tomb_value':'false'}):
            with self.subTest(options=options),self.assertRaises(m.Failure):
                m.revocation_state(self.revocation_xml(**options),self.base,'demo')

    def test_queue_completion_requires_original_and_new_tombstones_to_survive(self):
        identity='a'*64;previous={'b'*64}
        for state in ({'pending':{},'tombstones':previous},
                      {'pending':{identity:'frozen'},'tombstones':previous|{identity}},
                      {'pending':{},'tombstones':{identity}}):
            with self.assertRaises(m.Failure):m.require_logout_progress(state,identity,previous,drained=True)
        raw=self.revocation_xml(pending=False)
        root=ET.fromstring(raw);ET.SubElement(root,'string',{'name':'corrupt-pending-key'}).text='opaque'
        with self.assertRaises(m.Failure):m.revocation_state(ET.tostring(root),self.base,'demo')

    def test_revocation_private_whitelist_and_new_phase_are_explicit(self):
        run=m.Acceptance.__new__(m.Acceptance);run.package='io.stridehub.app.debug';run.args=types.SimpleNamespace(package='debug')
        calls=[];run.ui=types.SimpleNamespace(adb=lambda *args:calls.append(args) or b'<map/>')
        self.assertEqual(b'<map/>',run.private('shared_prefs/native_revocations.xml'))
        with self.assertRaises(m.Failure):run.private('shared_prefs/unrelated.xml')
        self.assertEqual(1,len(calls))
        args=m.parse_args(['device-offline','--base','https://unused.invalid','--credentials','unused','--output','unused'])
        self.assertEqual(90,args.offline_timeout);self.assertEqual(180,args.revocation_timeout)

    def phase_fixture(self):
        run=m.Acceptance.__new__(m.Acceptance);run.package='io.stridehub.app';run.args=types.SimpleNamespace(offline_timeout=1,revocation_timeout=10,timeout=1,package='release')
        run.base='https://nas-fixture.example'+m.nas.PREFIX;run.credentials={'username':'demo','password':'synthetic-only'};run.report={}
        run.guard_device=lambda:None;run.baseline=lambda:{'session':{'cipherSha256':'original'}}
        run.signed_in_ui=lambda:None;run.verify_data=lambda baseline:None;run.session_snapshot=lambda persistent:{'cipherSha256':'original'}
        run.private=lambda relative,optional=False:b'<map/>';run.passed=lambda name:None
        calls=[]
        def adb(*args):
            calls.append(args)
            if args[:3]==('shell','settings','get'):return b'1' if args[-1]=='wifi_on' else b'0'
            return b''
        run.ui=types.SimpleNamespace(adb=adb,visible=lambda label:False,tab=lambda label:None,tap=lambda *args,**kwargs:None,
            wait=lambda *args,**kwargs:None,screenshot=lambda name:None,fill=lambda *args:None)
        return run,calls

    def test_offline_phase_restores_original_network_on_cache_assertion_failure(self):
        run,calls=self.phase_fixture()
        def wait(label,**kwargs):
            if label=='离线缓存':raise m.Failure('fixture timeout')
        run.ui.wait=wait
        with patch.object(m.time,'sleep'),self.assertRaises(m.Failure):run.device_offline()
        self.assertEqual([('shell','svc','wifi','enable'),('shell','svc','data','disable')],calls[-2:])

    def test_offline_logout_observes_native_pending_then_drain_without_claiming_token401(self):
        run,calls=self.phase_fixture();identity='a'*64;previous={'b'*64}
        empty={'pending':{},'tombstones':previous}
        pending={'pending':{identity:'frozen'},'tombstones':previous|{identity}}
        drained={'pending':{},'tombstones':previous|{identity}}
        states=iter([empty,pending,pending,pending,drained,drained]);run.revocation_snapshot=lambda:next(states)
        with patch.object(m.time,'sleep'):run.device_logout()
        self.assertTrue(run.report['logout']['nativeQueueDrainedAfterNetworkRestore'])
        self.assertTrue(run.report['logout']['logoutTombstoneRetained'])
        self.assertFalse(run.report['deviceOriginalTokenServer401Observed'])
        self.assertIn(('shell','svc','wifi','enable'),calls)
        self.assertFalse(any('cmd' in args or 'jobscheduler' in args for args in calls))

    def test_failed_offline_logout_assertion_still_restores_both_original_network_states(self):
        run,calls=self.phase_fixture();empty={'pending':{},'tombstones':set()}
        run.revocation_snapshot=lambda:empty
        with patch.object(m.time,'sleep'),self.assertRaises(m.Failure):run.device_logout()
        self.assertEqual([('shell','svc','wifi','enable'),('shell','svc','data','disable')],calls[-2:])

    def overview_tree(self,banner=None,progress=False,extra=None):
        root=ET.Element('hierarchy')
        ET.SubElement(root,'node',{'text':'最近的真实记录'})
        if banner:ET.SubElement(root,'node',{'text':banner})
        if progress:ET.SubElement(root,'node',{'class':'android.widget.ProgressBar'})
        if extra:ET.SubElement(root,'node',{'text':extra})
        return root

    def test_online_snapshot_rejects_cache_preview_offline_and_progress_indicators(self):
        for tree in (self.overview_tree('缓存预览，正在核对'),self.overview_tree('离线缓存'),
                     self.overview_tree(progress=True),self.overview_tree(extra='连接并登录')):
            self.assertFalse(m.online_overview_snapshot(tree)[0])
        self.assertTrue(m.online_overview_snapshot(self.overview_tree())[0])

    def test_idle_wait_does_not_accept_old_ready_frame_before_loading_and_requires_two_new_stable_polls(self):
        run=m.Acceptance.__new__(m.Acceptance);run.args=types.SimpleNamespace(timeout=10)
        frames=[self.overview_tree(),self.overview_tree(progress=True),self.overview_tree('缓存预览，正在核对'),
                self.overview_tree(extra='updated'),self.overview_tree(extra='updated')]
        seen=[]
        def tree():seen.append(True);return frames.pop(0)
        run.ui=types.SimpleNamespace(tree=tree)
        with patch.object(m.time,'sleep'):run.wait_online_idle()
        self.assertEqual(5,len(seen));self.assertFalse(frames)

    def test_signed_in_ui_waits_for_idle_after_refresh_and_after_return_from_settings(self):
        run=m.Acceptance.__new__(m.Acceptance);run.package='io.stridehub.app';run.base='https://nas-fixture.example'+m.nas.PREFIX
        run.credentials={'username':'demo'};events=[]
        run.ui=types.SimpleNamespace(adb=lambda *args:b'',visible=lambda label:False,
            tab=lambda label:None,wait=lambda label:None,texts=lambda:[run.base,'demo'],
            tap=lambda label:events.append(label))
        run.wait_online_idle=lambda:events.append('idle-two-polls')
        with patch.object(m.time,'sleep'):run.signed_in_ui()
        self.assertEqual(['刷新','idle-two-polls','设置与更多','返回','idle-two-polls'],events)

    def test_android_package_identity_accepts_app_id_or_legacy_user_id(self):
        run=m.Acceptance.__new__(m.Acceptance);run.package='io.stridehub.app'
        run.args=types.SimpleNamespace(package='release')
        for key in ('userId','appId'):
            raw=f'{key}=10209\nversionCode=5 minSdk=26\n'.encode()
            run.ui=types.SimpleNamespace(adb=lambda *args:raw)
            self.assertEqual({'versionCode':5,'uid':'10209'},run.package_info())

    def metadata(self):return {'apiVersion':1,'capabilities':[m.CAPABILITY],'persistent':True,'expiresAt':None}

    def test_permanent_identity_requires_explicit_boolean_null_and_capability(self):
        m.persistent_metadata(self.metadata())
        for key,value in [('persistent',False),('persistent','true'),('expiresAt','null'),('expiresAt',''),('capabilities',[]),('apiVersion',2)]:
            data=self.metadata();data[key]=value
            with self.subTest(key=key),self.assertRaises(m.Failure):m.persistent_metadata(data)
        data=self.metadata();del data['expiresAt']
        with self.assertRaises(m.Failure):m.persistent_metadata(data)

    def test_legacy_default_is_seven_days_and_does_not_accept_null_or_persistent(self):
        expiry=(datetime.now(timezone.utc)+timedelta(days=7)).isoformat()
        m.legacy_metadata({'persistent':False,'expiresAt':expiry})
        for data in ({'expiresAt':None},{'expiresAt':expiry,'persistent':True},
                     {'expiresAt':datetime.now(timezone.utc).isoformat()}):
            with self.assertRaises(m.Failure):m.legacy_metadata(data)

    def test_private_connection_rejects_prod_another_account_or_old_prefix(self):
        base='https://nas-fixture.example'+m.nas.PREFIX
        m.require_connection({'server':base,'username':'demo'},base,'demo')
        for values in ({'server':'https://nas-fixture.example','username':'demo'},
                       {'server':base,'username':'another'},{}):
            with self.assertRaises(m.Failure):m.require_connection(values,base,'demo')

    def test_android_null_expiry_alone_never_proves_a_persistent_session(self):
        m.require_persistent_preferences({'persistent':'true','token':'encrypted:iv'})
        for values in ({'token':'encrypted:iv'}, {'persistent':'true','token':'shmobile_plaintext'},
                       {'persistent':'true','token':'encrypted:iv','expiresAt':'2026-11-01T00:00:00Z'}):
            with self.assertRaises(m.Failure):m.require_persistent_preferences(values)

    def test_private_preferences_are_parsed_without_retaining_raw_xml(self):
        values=m.preference_values(b'<map><string name="server">https://example.invalid/install</string><boolean name="persistent" value="true"/></map>')
        self.assertEqual('true',values['persistent'])
        with self.assertRaises(m.Failure):m.preference_values(b'not XML')

    def test_device_guard_stops_before_private_read_on_wrong_avd_or_version(self):
        run=m.Acceptance.__new__(m.Acceptance);run.package='io.stridehub.app'
        run.args=types.SimpleNamespace(phase='device-before')
        run.ui=types.SimpleNamespace(adb=lambda *args:b'not-owned-avd\n')
        run.private=lambda *args:self.fail('must not read any app files outside owned device/version')
        with self.assertRaises(m.Failure):run.guard_device()
        run.ui=types.SimpleNamespace(adb=lambda *args:b'stridehub_native\n')
        run.package_info=lambda:{'versionCode':99,'uid':'10000'}
        with self.assertRaises(m.Failure):run.guard_device()

    def test_scale_digest_detects_changed_queue_binding_payload_status_and_uuid(self):
        baseline={'measurements':[[1,'uuid-a',71.4]],'sync_queue':[[1,'uuid-a','device-a','frozen','pending']]}
        for index in range(1,5):
            changed={k:[list(row) for row in rows] for k,rows in baseline.items()}
            changed['sync_queue'][0][index]='changed'
            self.assertNotEqual(m.digest(baseline),m.digest(changed))
        changed={**baseline,'measurements':[[1,'different-uuid',71.4]]}
        self.assertNotEqual(m.digest(baseline),m.digest(changed))

    def test_unknown_phase_and_package_are_rejected_without_contacting_tools(self):
        with patch('sys.stderr'):
            with self.assertRaises(SystemExit):m.parse_args(['device-after','--base','https://unused.invalid','--credentials','unused','--output','unused','--package','production-device'])


if __name__=='__main__':unittest.main()
