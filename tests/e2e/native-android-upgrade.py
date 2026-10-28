#!/usr/bin/env python3
"""Synthetic legacy-data upgrade check on this task's dedicated debug emulator only."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time
import re
import xml.etree.ElementTree as ET

PACKAGE = 'io.stridehub.app.debug'
SERIAL = 'emulator-5556'

def adb(*args, **kwargs):
    return subprocess.check_output(['adb', '-s', SERIAL, *args], **kwargs)

def open_legacy_weighing():
    adb('shell','am','start','-n',PACKAGE+'/io.stridehub.app.MainActivity')
    adb('shell','uiautomator','dump','/sdcard/native-upgrade.xml')
    root=ET.fromstring(adb('shell','cat','/sdcard/native-upgrade.xml'))
    node=next(n for n in root.iter('node') if '离线称重' in n.get('text',''))
    x,y,xx,yy=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
    time.sleep(2)

def snapshot(connection):
    return {name: connection.execute(f'SELECT * FROM {name} ORDER BY 1').fetchall()
            for name in ('measurements','measurement_derivations','legacy_imports','sync_queue')}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('mode', choices=['seed','verify'])
    parser.add_argument('--output', default='var/native-artifacts')
    args=parser.parse_args()
    if adb('emu','avd','name').decode().splitlines()[0].strip() != 'stridehub_native':
        raise SystemExit('Refusing to touch any device except the dedicated stridehub_native emulator.')
    version=adb('shell','dumpsys','package',PACKAGE).decode()
    if 'DEBUGGABLE' not in version:
        raise SystemExit('Refusing to touch a release application.')
    if args.mode == 'seed' and 'versionCode=2 ' not in version:
        raise SystemExit('Seed requires the actual legacy 0.2.0 (versionCode 2) APK.')
    if args.mode == 'verify' and 'versionCode=4 ' not in version:
        raise SystemExit('Verification requires the new native versionCode 4 APK.')
    adb('shell','am','force-stop',PACKAGE)
    if args.mode=='seed':
        open_legacy_weighing()
        adb('shell','am','force-stop',PACKAGE)
    output=Path(args.output); output.mkdir(parents=True,exist_ok=True)
    database=output / ('preupgrade.db' if args.mode=='seed' else 'postupgrade.db')
    database.write_bytes(adb('exec-out','run-as',PACKAGE,'cat','databases/tinyyumi.db'))
    db=sqlite3.connect(database)
    if args.mode=='seed':
        if db.execute('SELECT COUNT(*) FROM measurements').fetchone()[0]:
            raise SystemExit('Legacy fixture must start from an empty dedicated application.')
        now=int(time.time()*1000)-86400000
        for i,status in enumerate(['pending','synced','conflict','error','revoked']):
            row=900+i; uuid=f'00000000-0000-4000-8000-{row:012d}'
            db.execute('INSERT INTO measurements(id,measured_at,local_day,weight,impedance,source,upload_uuid,captured_time) VALUES(?,?,?,?,?,?,?,?)',
                       (row,now+i,'2026-10-22',70+i/10,0,'manual',uuid,'2026-10-22T08:00:00+08:00'))
            db.execute('INSERT INTO sync_queue(measurement_id,uuid,binding,server,payload,status,detail) VALUES(?,?,?,?,?,?,?)',
                       (row,uuid,'original-device','https://original.example','{"synthetic":true}',status,'DEMO upgrade fixture'))
        db.execute('INSERT INTO legacy_imports(fingerprint,measurement_id,original_row) VALUES(?,?,?)',('deleted-import-fixture',899,'DEMO deleted import tombstone'))
        db.commit()
        db.close()
        adb('push',str(database),'/data/local/tmp/stridehub-native-upgrade.db')
        adb('shell','run-as',PACKAGE,'sh','-c',"'cat /data/local/tmp/stridehub-native-upgrade.db > databases/tinyyumi.db'")
        # Freeze metrics after the old reader performs its usual missing-value backfill.
        open_legacy_weighing()
        adb('shell','am','force-stop',PACKAGE)
        database.write_bytes(adb('exec-out','run-as',PACKAGE,'cat','databases/tinyyumi.db'))
        db=sqlite3.connect(database);expected=snapshot(db);db.close()
        if len(expected['measurement_derivations']) != 5:
            raise SystemExit('Legacy weighing screen must load every synthetic record before freezing the baseline.')
        (output/'upgrade-expected.json').write_text(json.dumps(expected,ensure_ascii=False,indent=2))
        print('Seeded legacy APK: 5 records and original derivations, 5 queue states, 1 deleted-import tombstone.')
    else:
        actual=json.loads(json.dumps(snapshot(db)));db.close()
        expected=json.loads((output/'upgrade-expected.json').read_text())
        assert actual==expected, 'Upgrade changed legacy measurements, queue bindings/payloads/states, or tombstones'
        digest=hashlib.sha256(json.dumps(actual,sort_keys=True).encode()).hexdigest()
        (output/'upgrade-result.json').write_text(json.dumps({'passed':True,'legacyDataSha256':digest},indent=2))
        print('PASS: legacy records, UUIDs, all 5 queue states and tombstone are unchanged after upgrade.')

if __name__=='__main__': main()
