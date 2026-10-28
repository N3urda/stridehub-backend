#!/usr/bin/env python3
"""Put a verified release APK and its metadata into an image's public/downloads directory."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    subprocess.run([str(args.build_tools / 'apksigner'), 'verify', str(args.apk)], check=True)
    badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or package[1] != 'io.stridehub.app' or 'application-debuggable' in badging or not sdk:
        raise SystemExit('Only a signed, non-debuggable StrideHub release may be published')
    version = package[3]
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?', version):
        raise SystemExit('Unsupported version name')
    android_versions = {26: '8.0', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12', 33: '13', 34: '14', 35: '15', 36: '16'}
    if int(sdk[1]) not in android_versions:
        raise SystemExit('Unknown minimum Android version; update the publisher mapping')
    data = args.apk.read_bytes()
    filename = 'StrideHub-' + version + '.apk'
    release = {'version': version, 'versionCode': int(package[2]), 'minAndroid': android_versions[int(sdk[1])], 'filename': filename, 'sha256': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / filename
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != release['sha256']:
        raise SystemExit('This version already exists with different bytes; publish a new version')
    if target.resolve() != args.apk.resolve():
        shutil.copyfile(args.apk, target)
    (args.output / (filename + '.sha256')).write_text(release['sha256'] + '  ' + filename + '\n')
    manifest = args.output / 'android-release.json'
    temporary = manifest.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(release, indent=2) + '\n')
    temporary.replace(manifest)
    print('Published Android ' + version + ' with matching release metadata')


if __name__ == '__main__':
    main()
