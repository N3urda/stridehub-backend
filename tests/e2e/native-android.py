#!/usr/bin/env python3
"""Native APK acceptance on the dedicated synthetic emulator/backend; never clears app data.

Run from repo root with ANDROID_HOME and a private --credentials JSON containing
only the disposable backend username/password. UI writes use DEMO fixture names.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT=Path.cwd()
OUT=ROOT/'var/native-artifacts'
PACKAGE='io.stridehub.app.debug'
SERIAL='emulator-5556'
CONTAINER='stridehub-native-e2e'
ADB=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
BASE='http://127.0.0.1:8086'
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def adb(*args):
    try:
        return subprocess.check_output([ADB,'-s',SERIAL,*args],stderr=subprocess.STDOUT,timeout=45)
    except subprocess.SubprocessError:
        # input text may contain the disposable account password. Never include
        # command arguments or captured process output in exception diagnostics.
        raise RuntimeError('ADB operation failed; sensitive command arguments omitted') from None

def guard():
    assert adb('emu','avd','name').decode().splitlines()[0]=='stridehub_native'
    package=adb('shell','dumpsys','package',PACKAGE).decode()
    assert 'DEBUGGABLE' in package and 'versionCode=4 ' in package
    info=json.loads(subprocess.check_output(['docker','inspect',CONTAINER]))[0]
    env=set(info['Config']['Env'])
    assert 'STRIDEHUB_NATIVE_E2E=1' in env
    assert 'DATABASE_DIRECTORY=/var/www/var/native-e2e/db' in env
    mounts={m['Destination']:Path(m['Source']).resolve() for m in info['Mounts']}
    assert mounts['/var/www']==ROOT.resolve()
    assert mounts['/var/www/watch']==(ROOT/'var/native-e2e/watch').resolve()
    assert any(p['HostPort']=='8086' and p['HostIp']=='127.0.0.1' for p in info['NetworkSettings']['Ports']['8080/tcp'])

def ui():
    for _ in range(4):
        try:
            adb('shell','uiautomator','dump','/sdcard/stridehub-native.xml')
            root=ET.fromstring(adb('shell','cat','/sdcard/stridehub-native.xml'))
            assert all(n.get('class')!='android.webkit.WebView' for n in root.iter('node'))
            return root
        except ET.ParseError: time.sleep(.25)
    raise AssertionError('Cannot inspect native UI')

def texts(root=None):
    return [n.get('text') or n.get('content-desc') for n in (ui() if root is None else root).iter('node') if n.get('text') or n.get('content-desc')]

def matches(root,label,partial=False):
    return [n for n in root.iter('node') if label in (n.get('text'),n.get('content-desc')) or partial and (label in n.get('text','') or label in n.get('content-desc',''))]

def point(node):
    x,y,xx,yy=map(int,re.findall(r'\d+',node.get('bounds')))
    assert xx>x and yy>y
    return str((x+xx)//2),str((y+yy)//2)

def tap(label,scroll=False,partial=False):
    for _ in range(9 if scroll else 3):
        nodes=matches(ui(),label,partial)
        if nodes:
            adb('shell','input','tap',*point(nodes[-1]));time.sleep(.4);return
        if scroll:adb('shell','input','swipe','540','1500','540','550','300')
        else:time.sleep(.3)
    raise AssertionError('Native control not visible: '+label)

def wait(label,timeout=30,partial=False):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if matches(ui(),label,partial):return
        time.sleep(.3)
    raise AssertionError('Expected native text: '+label)

def fill(label,value):
    def fields(root):
        return [node for node in root.iter('node') if node.get('class')=='android.widget.EditText' and (node.get('content-desc')==label or any(label==child.get('text') for child in node))]
    # A partially clipped field may expose only its label at the navigation
    # bar. Bring its input area into view before typing into the focused editor.
    for _ in range(12):
        candidates=fields(ui())
        if candidates:
            field=candidates[-1]
            x,y,xx,yy=map(int,re.findall(r'\d+',field.get('bounds')))
            if yy-y>=100 and y>=250 and yy<=1500:break
            if y<250:
                adb('shell','input','swipe','540','500','540','1000','250');continue
        adb('shell','input','swipe','540','1350','540','850','250')
    else:raise AssertionError('Native input is not fully visible: '+label)
    adb('shell','input','tap',*point(field))
    if not any(node.get('focused')=='true' for node in fields(ui())):
        raise AssertionError('Native input did not receive focus: '+label)
    adb('shell','input','keycombination','113','29')
    adb('shell','input','keyevent','67')
    adb('shell','input','text',value.replace(' ','%s'))
    # Hardware-keyboard configurations may leave the IME hidden. An
    # unconditional Back would then leave the editor rather than dismiss it.
    time.sleep(.35)
    if 'mInputShown=true' in adb('shell','dumpsys','input_method').decode():
        adb('shell','input','keyevent','4')

def tab(label):
    tap(label)
    for _ in range(8):
        if not matches(ui(),'返回'):return
        tap('返回')
    raise AssertionError('Native navigation stack did not unwind')

def shot(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))

def api(path,method='GET',body=None,token=''):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path,headers=headers,method=method,data=None if body is None else json.dumps(body).encode())
    with HTTP.open(req,timeout=30) as response:
        raw=response.read();return json.loads(raw) if raw else {}

def login(credentials):
    adb('shell','am','start','-n',PACKAGE+'/io.stridehub.app.MainActivity')
    if matches(ui(),'连接 StrideHub'):
        fill('服务器地址','http://10.0.2.2:8086');fill('用户名',credentials['username']);fill('密码',credentials['password'])
        check=next(n for n in ui().iter('node') if n.get('class')=='android.widget.CheckBox')
        if check.get('checked')!='true':adb('shell','input','tap',*point(check))
        tap('连接并登录',scroll=True)
    wait('总览');tab('总览');wait('最近的真实记录')
    # A dedicated emulator can still have been connected to another server.
    # Verify the live repository's identity before any UI write or logout.
    tap('设置与更多');wait('连接与账号')
    visible=texts()
    assert 'http://10.0.2.2:8086' in visible, 'Refusing UI actions against a non-fixture server'
    assert credentials['username'] in visible, 'Refusing UI actions for a non-fixture account'
    tap('返回');wait('最近的真实记录');shot('native-overview')

def navigation():
    for label,expect in [('身体','身体与恢复'),('运动','每一次运动'),('计划','计划与日常'),('总览','最近的真实记录')]:
        tab(label);wait(expect);shot('native-'+{'身体':'body','运动':'exercise','计划':'plans','总览':'overview'}[label])

def meal(token):
    label='DEMO Native '+str(int(time.time()))
    tab('计划');tap('饮食记录',scroll=True);wait('新增饮食记录');tap('新增饮食记录')
    tap('早餐');fill('食物 *',label);fill('份量','1 bowl');tap('保存饮食记录',scroll=True)
    wait(label);rows=api('/api/v1/health/meals',token=token)['records']
    record=next(row for row in rows if row['foods']==label)
    assert record['version']==1 and record['portion']=='1 bowl'
    shot('native-meal-save')
    tap(label);fill('食物 *',label+' updated');tap('保存饮食记录',scroll=True);wait(label+' updated')
    record=api('/api/v1/health/meals/'+record['id'],token=token)
    assert record['version']==2 and record['foods']==label+' updated'
    tap(label+' updated');tap('删除此记录',scroll=True);tap('删除');wait('新增饮食记录')
    assert all(row['id']!=record['id'] for row in api('/api/v1/health/meals',token=token)['records'])

def offline():
    tab('总览');wait('最近的真实记录')
    subprocess.run(['docker','pause',CONTAINER],check=True,stdout=subprocess.DEVNULL)
    try:
        tap('刷新');wait('离线缓存',timeout=55,partial=True);shot('native-offline')
    finally:subprocess.run(['docker','unpause',CONTAINER],check=True,stdout=subprocess.DEVNULL)
    tap('刷新')
    deadline=time.monotonic()+55
    while time.monotonic()<deadline:
        visible=texts()
        if '最近的真实记录' in visible and not any('离线缓存' in text or '缓存预览，正在核对' in text for text in visible):
            shot('native-reconnected');return
        time.sleep(.3)
    raise AssertionError('Reconnect did not replace the offline/pending snapshot with a confirmed response')

def plans(token):
    plan=api('/api/v1/health/plans/native-demo-plan',token=token)
    tab('计划');tap('健康计划',scroll=True);tap(plan['title']);fill('目标 *','DEMO native plan verified')
    tap('保存健康计划',scroll=True);wait(plan['title'])
    changed=api('/api/v1/health/plans/native-demo-plan',token=token)
    assert changed['version']==plan['version']+1 and changed['goal']=='DEMO native plan verified'
    assert changed['items']==plan['items']
    tab('计划');tap('训练课表',scroll=True)
    session=api('/api/v1/training/sessions/native-demo-session',token=token)
    tap(session['title']);fill('课表名称 *','DEMO native training verified')
    tap('执行反馈',scroll=True)
    root=ui();parents={c:p for p in root.iter('node') for c in p}
    node=next(n for n in root.iter('node') if n.get('text')=='执行反馈')
    while node is not None:
        switches=[n for n in node.iter('node') if n.get('checkable')=='true']
        if switches:
            if switches[0].get('checked')!='true':adb('shell','input','tap',*point(switches[0]))
            break
        node=parents.get(node)
    tap('主观强度 RPE (1–10)',scroll=True);fill('主观强度 RPE (1–10)','4')
    tap('保存训练课表',scroll=True);wait('DEMO native training verified')
    changed=api('/api/v1/training/sessions/native-demo-session',token=token)
    assert changed['feedback']['rpe']==4 and changed['version']==session['version']+1
    shot('native-plan-feedback')

def sync(token):
    source=api('/api/v1/mobile/sync',token=token)
    assert source['sources']['daily']['state']=='not_configured', 'Synthetic fixture must have no real Zepp credential'
    previous={row['id'] for row in source['records']}
    tab('总览');tap('设置与更多');tap('Zepp 同步与任务状态');wait('同步每日健康');tap('同步每日健康')
    time.sleep(1)
    records=api('/api/v1/mobile/sync',token=token)['records']
    job=next(row for row in records if row['id'] not in previous)
    assert job['state']=='queued'
    with (OUT/'native-sync-worker.txt').open('w') as output:
        subprocess.run(['docker','exec',CONTAINER,'php','bin/console','app:mobile:workflows:run','--limit=10'],check=True,stdout=output,stderr=subprocess.STDOUT)
    job=api('/api/v1/mobile/sync/'+job['id'],token=token)
    assert job['state']=='failed' and job['result']['state']=='not_configured'
    tap('刷新');tap('任务记录',scroll=True);wait('每日健康 · 失败');shot('native-sync')

def logout():
    tab('总览');tap('设置与更多');tap('退出 / 切换服务器或账号',scroll=True);wait('连接 StrideHub')
    tap('离线称重与本机历史',scroll=True);wait('身体记录');shot('native-offline-weighing')
    tap('返回 StrideHub');wait('连接 StrideHub')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--credentials',type=Path,required=True)
    parser.add_argument('--scenario',choices=['navigation','meal','plans','offline','sync','logout','all'],default='all')
    args=parser.parse_args();guard();OUT.mkdir(parents=True,exist_ok=True)
    credentials=json.loads(args.credentials.read_text())
    session=api('/mobile/v1/sessions','POST',{**credentials,'deviceName':'DEMO native acceptance observer'})
    token=session['token'];passed=[]
    try:
        login(credentials);passed.append('native login/overview')
        actions={'navigation':navigation,'meal':lambda:meal(token),'plans':lambda:plans(token),'offline':offline,'sync':lambda:sync(token),'logout':logout}
        for name,action in actions.items():
            if args.scenario in ('all',name):action();passed.append(name);print('PASS',name,flush=True)
    finally:api('/mobile/v1/session','DELETE',token=token)
    (OUT/('acceptance-'+args.scenario+'.json')).write_text(json.dumps({'passed':passed},indent=2))
    print('PASS native acceptance:',', '.join(passed))

if __name__=='__main__':main()
