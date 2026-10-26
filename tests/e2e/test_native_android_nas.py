"""Offline tests of the public NAS harness guards; no devices or network are contacted."""
import importlib.util
import copy
from pathlib import Path
import subprocess
import shlex
import xml.etree.ElementTree as ET
import sys
import types
import unittest
from unittest.mock import patch

SOURCE=Path(__file__).with_name('native-android-nas.py')
spec=importlib.util.spec_from_file_location('native_android_nas_tested',SOURCE)
nas=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=nas
spec.loader.exec_module(nas)


class InputUi(nas.NativeUi):
    """Stateful Android input fixture: selection chord is deliberately unsupported."""
    def __init__(self,label,old,ignore_delete=False,corrupt_input=False):
        self.label=label;self.value=old;self.cursor=2;self.focus=False;self.masked=True
        self.ignore_delete=ignore_delete;self.corrupt_input=corrupt_input;self.calls=[]
        self.legacy=nas.legacy_ui_module()
        self.legacy.point=lambda node: ('900','350') if node.get('content-desc') in ('显示密码','隐藏密码') else ('500','350')
    def tree(self):
        root=ET.Element('hierarchy')
        field=ET.SubElement(root,'node',{'class':'android.widget.EditText','bounds':'[100,300][1000,450]',
            'focused':str(self.focus).lower(),'text':'•'*len(self.value) if self.label=='密码' and self.masked else self.value})
        ET.SubElement(field,'node',{'text':self.label})
        if self.label=='密码':ET.SubElement(root,'node',{'content-desc':'显示密码' if self.masked else '隐藏密码'})
        return root
    def adb(self,*args,**kwargs):
        self.calls.append(args)
        if args[:3]==('shell','input','tap'):
            if args[3]=='900':self.masked=not self.masked
            else:self.focus=True
        elif args[:3]==('shell','input','keyevent'):
            for key in args[3:]:
                if key=='123':self.cursor=len(self.value)
                elif key=='67' and not self.ignore_delete and self.cursor:
                    self.value=self.value[:self.cursor-1]+self.value[self.cursor:];self.cursor-=1
                elif key=='112' and not self.ignore_delete:
                    self.value=self.value[:self.cursor]+self.value[self.cursor+1:]
        elif len(args)==2 and args[1].startswith('input text '):
            text=shlex.split(args[1])[2].replace('%s',' ')
            if self.corrupt_input:text+='unexpected'
            self.value=self.value[:self.cursor]+text+self.value[self.cursor:];self.cursor+=len(text)
        return b''


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

    def test_fill_replaces_old_url_without_supported_selection_shortcut_and_reads_exact_value(self):
        ui=InputUi('服务器地址','http://127.0.0.1:8086')
        target='https://nas-fixture.example:1999'+nas.PREFIX
        with patch.object(nas.time,'sleep'):ui.fill('服务器地址',target)
        self.assertEqual(target,ui.value)
        self.assertFalse(any('keycombination' in call for call in ui.calls))
        self.assertTrue(any(call[:3]==('shell','input','keyevent') and '67' in call for call in ui.calls))

    def test_fill_refuses_to_append_when_clearing_did_not_work(self):
        ui=InputUi('服务器地址','old value',ignore_delete=True)
        with patch.object(nas.time,'sleep'),self.assertRaises(nas.SafeFailure):
            ui.fill('服务器地址','new-value')
        self.assertFalse(any(len(call)==2 and call[1].startswith('input text ') for call in ui.calls))
        self.assertEqual('old value',ui.value)

    def test_password_exact_readback_handles_percent_s_and_restores_mask_without_output(self):
        secret='fixture%s $value&'
        ui=InputUi('密码','old-password')
        with patch.object(nas.time,'sleep'):ui.fill('密码',secret)
        self.assertEqual(secret,ui.value)
        self.assertTrue(ui.masked)
        self.assertIn(('shell','rm','-f','/sdcard/stridehub-native.xml'),ui.calls)

    def test_password_readback_mismatch_fails_redacted_and_restores_mask(self):
        secret='fixture-secret'
        ui=InputUi('密码','old-password',corrupt_input=True)
        with patch.object(nas.time,'sleep'),self.assertRaises(nas.SafeFailure) as error:
            ui.fill('密码',secret)
        self.assertNotIn(secret,str(error.exception))
        self.assertNotIn('unexpected',str(error.exception))
        self.assertTrue(ui.masked)
        self.assertIn(('shell','rm','-f','/sdcard/stridehub-native.xml'),ui.calls)

    def test_login_exercises_copied_web_address_and_friendly_wrong_password_before_valid_login(self):
        class Ui:
            def __init__(self):self.fills=[];self.waits=[];self.buttons=[]
            def adb(self,*args,**kwargs):return b''
            def visible(self,label):return label=='连接并登录'
            def fill(self,label,value):self.fills.append((label,value))
            def checkbox(self,*args):pass
            def tap(self,label,**kwargs):self.buttons.append(label)
            def wait(self,label,**kwargs):self.waits.append(label)
            def screenshot(self,label):pass
        run=nas.NasAcceptance.__new__(nas.NasAcceptance)
        run.ui=Ui();run.base='https://nas-fixture.example'+nas.PREFIX
        run.credentials={'username':'synthetic','password':'synthetic-secret'}
        run.args=types.SimpleNamespace(http_timeout=45);run.report={}
        identity=[];run.ensure_ui_identity=lambda:identity.append(True)
        with patch.object(nas.time,'sleep'):run.login()
        self.assertEqual(('服务器地址',run.base+'/admin/app'),run.ui.fills[0])
        passwords=[value for label,value in run.ui.fills if label=='密码']
        self.assertEqual([run.credentials['password']+'-deliberately-invalid',run.credentials['password']],passwords)
        self.assertEqual(['用户名或密码错误','最近的真实记录'],run.ui.waits)
        self.assertEqual(['连接并登录','连接并登录'],run.ui.buttons)
        self.assertEqual([True],identity)
        self.assertTrue(run.report['loginInput']['friendlyInvalidPasswordShown'])

    def test_logged_in_detail_resume_navigates_overview_then_checks_existing_identity(self):
        events=[]
        class Ui:
            def adb(self,*args,**kwargs):return b''
            def visible(self,label):return False
            def tab(self,label):events.append(('tab',label))
            def wait(self,label):events.append(('wait',label))
            def screenshot(self,label):events.append(('screenshot',label))
        run=nas.NasAcceptance.__new__(nas.NasAcceptance);run.ui=Ui()
        run.ensure_ui_identity=lambda:events.append(('identity','checked'))
        with patch.object(nas.time,'sleep'):run.login()
        self.assertEqual([('tab','总览'),('wait','最近的真实记录'),('identity','checked'),
                          ('screenshot','login-public-https')],events)

    def test_running_coverage_uses_section_and_merged_metric_label_without_exact_row_match(self):
        class Ui:
            def __init__(self):self.shots=[];self.coverage_seen=False;self.metric_seen=False
            def tap(self,label,scroll=False,partial=False):
                if label=='数据覆盖与记录缺口':self.coverage_seen=True
                elif label=='记录断档':
                    self.assert_coverage_order=self.coverage_seen
                    if not partial or not self.coverage_seen:raise nas.SafeFailure('Merged coverage label was not discoverable')
                    # A native merged row includes the value after its label.
                    if label not in '记录断档 秒, 0':raise nas.SafeFailure('Expected coverage metric absent')
                    self.metric_seen=True
                elif label=='记录断档 秒':raise nas.SafeFailure('Exact row is unavailable in merged semantics')
            def screenshot(self,name):self.shots.append(name)
        run=nas.NasAcceptance.__new__(nas.NasAcceptance);run.ui=Ui()
        run.capture_running_sections()
        self.assertTrue(run.ui.metric_seen)
        self.assertTrue(run.ui.assert_coverage_order)
        self.assertIn('running-coverage',run.ui.shots)
        self.assertEqual('running-splits',run.ui.shots[-1])

    def test_top_seek_reveals_restored_offscreen_heading_without_tapping(self):
        ui=nas.NativeUi.__new__(nas.NativeUi);position=[3];calls=[]
        ui.visible=lambda label:position[0]==0 and label=='跑步复盘'
        def adb(*args):
            calls.append(args)
            if args[:3]==('shell','input','swipe'):position[0]=max(0,position[0]-1)
            return b''
        ui.adb=adb
        with patch.object(nas.time,'sleep'):ui.scroll_to_top('跑步复盘')
        self.assertEqual(0,position[0])
        self.assertEqual(3,len(calls))
        self.assertTrue(all(call==('shell','input','swipe','540','600','540','1600','220') for call in calls))
        with patch.object(nas.time,'sleep'):ui.scroll_to_top('跑步复盘')
        self.assertEqual(3,len(calls))

    def test_top_seek_is_bounded_if_expected_page_never_appears(self):
        ui=nas.NativeUi.__new__(nas.NativeUi);calls=[]
        ui.visible=lambda label:False;ui.adb=lambda *args:calls.append(args)
        with patch.object(nas.time,'sleep'),self.assertRaises(nas.SafeFailure):
            ui.scroll_to_top('跑步复盘',attempts=2)
        self.assertEqual(2,len(calls))

    def test_tab_resume_returns_to_root_and_top_heading(self):
        ui=nas.NativeUi.__new__(nas.NativeUi);events=[];depth=[2]
        ui.visible=lambda label:label=='返回' and depth[0]>0
        def tap(label):
            events.append(('tap',label))
            if label=='返回':depth[0]-=1
        ui.tap=tap;ui.scroll_to_top=lambda heading:events.append(('top',heading))
        ui.tab('运动')
        self.assertEqual([('tap','运动'),('tap','返回'),('tap','返回'),('top','每一次运动')],events)

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
