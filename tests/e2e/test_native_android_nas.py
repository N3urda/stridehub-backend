"""Offline tests of the public NAS harness guards; no devices or network are contacted."""
import importlib.util
import copy
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

SOURCE=Path(__file__).with_name('native-android-nas.py')
spec=importlib.util.spec_from_file_location('native_android_nas_tested',SOURCE)
nas=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=nas
spec.loader.exec_module(nas)


class HarnessGuardsTest(unittest.TestCase):
    def test_only_exact_public_https_fixture_root_is_accepted(self):
        value='https://nas-fixture.example'+nas.PREFIX
        self.assertEqual(value,nas.validate_base(value+'/'))
        self.assertEqual(value,nas.validate_base('https://NAS-fixture.example:443'+nas.PREFIX))
        for bad in ['http://nas-fixture.example'+nas.PREFIX,
                    'https://nas-fixture.example',
                    value+'/admin',value+'?token=hidden',
                    'https://user:password@nas-fixture.example'+nas.PREFIX,
                    'https://nas-fixture.example/__stridehub_e2e_%32%30%32%36%31%30%32%36']:
            with self.assertRaises(nas.SafeFailure):nas.validate_base(bad)

    def test_legacy_helper_reuse_does_not_import_local_endpoint_or_environment(self):
        with patch.dict('os.environ',{},clear=True):
            helpers=nas.legacy_ui_module()
        self.assertTrue(callable(helpers.ui))
        self.assertFalse(hasattr(helpers,'BASE'))
        self.assertFalse(hasattr(helpers,'guard'))
        self.assertFalse(hasattr(helpers,'offline'))
        self.assertFalse(hasattr(helpers,'login'))

    def test_adb_exception_never_contains_password_command_or_stderr(self):
        ui=nas.NativeUi.__new__(nas.NativeUi);ui.adb_path='fake-adb'
        secret='fixture-secret-should-not-appear'
        with patch.object(subprocess,'check_output',side_effect=subprocess.CalledProcessError(1,['input',secret],output=secret.encode())):
            with self.assertRaises(nas.SafeFailure) as error:ui.adb('shell','input text '+secret)
        self.assertNotIn(secret,str(error.exception))

    def test_offline_context_restores_both_original_network_states_after_failure(self):
        calls=[]
        class Ui:
            def adb(self,*args):
                calls.append(args)
                if args[:3]==('shell','settings','get'):
                    return b'1' if args[-1]=='wifi_on' else b'0'
                return b''
        with self.assertRaisesRegex(nas.SafeFailure,'original failure'):
            with nas.android_offline(Ui()):raise nas.SafeFailure('original failure')
        self.assertEqual(calls[-2:],[('shell','svc','wifi','enable'),('shell','svc','data','disable')])

    def test_unknown_network_state_causes_no_network_changes(self):
        calls=[]
        class Ui:
            def adb(self,*args):calls.append(args);return b'unknown'
        with self.assertRaises(nas.SafeFailure):
            with nas.android_offline(Ui()):self.fail('must not enter')
        self.assertFalse(any(args[:2]==('shell','svc') for args in calls))

    def test_pairing_fails_closed_for_any_history_or_queue_and_missing_snapshot(self):
        nas.require_empty_pairing_history({'measurements':[],'queue':[]})
        for state in ({'measurements':[{'id':1}],'queue':[]},
                      {'measurements':[],'queue':[{'measurement_id':1}]}, {},
                      {'measurements':[]}, {'queue':[]}):
            with self.subTest(state=state),self.assertRaises(nas.SafeFailure):
                nas.require_empty_pairing_history(state)

    def test_weighing_scenario_aborts_before_pairing_or_server_reads_when_history_exists(self):
        class Ui:
            def __init__(self):self.taps=[]
            def tab(self,label):pass
            def tap(self,label,**kwargs):self.taps.append(label)
            def wait(self,label,**kwargs):pass
            def local_state(self):return {'measurements':[{'id':5}],'queue':[]}
        run=nas.NasAcceptance.__new__(nas.NasAcceptance);run.ui=Ui()
        # No API attribute exists: reaching any API request would fail this safety test.
        with self.assertRaises(nas.SafeFailure):run.weighing()
        self.assertEqual(['开始称重'],run.ui.taps)

    def test_only_one_exact_manual_record_with_canonical_uuid_is_accepted(self):
        row={'id':1,'weight':71.4,'source':'手工录入',
             'upload_uuid':'12345678-1234-1234-1234-123456789abc'}
        self.assertEqual(row,nas.require_single_manual_measurement({'measurements':[row]},71.4))
        invalid=[[],[row,row]]
        for field,value in [('weight',71.3),('weight',float('nan')),('weight','71.4'),
                            ('source','BLE'),('upload_uuid','-'*36)]:
            changed=dict(row);changed[field]=value;invalid.append([changed])
        for rows in invalid:
            with self.subTest(rows=rows),self.assertRaises(nas.SafeFailure):
                nas.require_single_manual_measurement({'measurements':rows},71.4)

    def test_public_capture_readback_rejects_duplicate_deleted_wrong_device_or_weight(self):
        identity='12345678-1234-1234-1234-123456789abc'
        row={'measurementId':identity,'deviceId':'test-device','deleted':False,
             'capture':{'weightKg':71.4}}
        self.assertEqual(row,nas.require_unique_server_capture([row],identity,71.4,'test-device'))
        invalid=[[],[row,row]]
        for field,value in [('deleted',True),('deleted',None),('deviceId','different-device')]:
            changed=copy.deepcopy(row);changed[field]=value;invalid.append([changed])
        for weight in (71.3,None,'71.4',float('nan')):
            changed=copy.deepcopy(row);changed['capture']['weightKg']=weight;invalid.append([changed])
        for rows in invalid:
            with self.subTest(rows=rows),self.assertRaises(nas.SafeFailure):
                nas.require_unique_server_capture(rows,identity,71.4,'test-device')

    def test_redirect_handler_never_replays_bearer_to_a_redirect_target(self):
        self.assertIsNone(nas.NoRedirect().redirect_request(None,None,302,'','', 'https://other.example'))


if __name__=='__main__':unittest.main()
