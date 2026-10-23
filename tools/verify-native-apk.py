#!/usr/bin/env python3
"""Reject embedded-browser creation or navigation in the shipped APK, including dependencies."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

FORBIDDEN = re.compile(r'(?:new-instance|invoke-\S+) .*Landroid/webkit/WebView;.*(?:<init>|loadUrl|loadData|evaluateJavascript)|new-instance .*Landroid/webkit/WebView;|invoke-\S+ .*Landroid/webkit/CookieManager;', re.I)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('apk',type=Path)
    parser.add_argument('--dexdump',type=Path,required=True)
    args=parser.parse_args()
    findings=[];count=0
    with tempfile.TemporaryDirectory(prefix='stridehub-native-apk-') as folder, zipfile.ZipFile(args.apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex',name):continue
            count+=1
            target=Path(folder)/name;target.write_bytes(archive.read(name))
            process=subprocess.Popen([str(args.dexdump),'-d',str(target)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,errors="replace")
            for line in process.stdout:
                if FORBIDDEN.search(line):findings.append(f'{name}: {line.strip()}')
            error=process.stderr.read();code=process.wait()
            if code:raise SystemExit(f'dexdump failed: {error}')
    if count==0:raise SystemExit('No DEX files found; refusing to pass an uninspected APK.')
    if findings:
        print('\n'.join(findings[:15]))
        raise SystemExit(f'FAIL: {len(findings)} embedded-browser/legacy-cookie bytecode references in {args.apk.name}.')
    print(f'PASS: inspected {count} DEX files; no WebView construction/navigation or CookieManager calls.')

if __name__=='__main__':main()
