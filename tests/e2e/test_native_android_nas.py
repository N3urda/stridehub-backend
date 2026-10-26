"""Offline tests of the public NAS harness guards; no devices or network are contacted."""
import importlib.util
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

    def test_redirect_handler_never_replays_bearer_to_a_redirect_target(self):
        self.assertIsNone(nas.NoRedirect().redirect_request(None,None,302,'','', 'https://other.example'))


if __name__=='__main__':unittest.main()
