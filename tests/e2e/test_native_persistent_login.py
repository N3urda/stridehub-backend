"""No-device/no-network guard and contract tests for persistent acceptance."""
import importlib.util
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
