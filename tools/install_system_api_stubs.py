#!/usr/bin/env python3
"""Install compile-only AOSP 34 system API stubs absent from the public SDK; never package them."""
import base64
import hashlib
import io
import os
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BLOB = '278c25727c4e6d8ac47f3e9fdfd6e43d4272ea37'
URL = 'https://android.googlesource.com/platform/prebuilts/sdk/+/refs/heads/android14-release/34/system/android.jar?format=TEXT'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'tools/android-sdk')))
public = SDK / 'platforms/android-36/android.jar'
if not public.is_file():
    raise SystemExit('Install the public Android 36 SDK first or set ANDROID_HOME.')
cache = Path.home() / '.cache/ghost-build/android-system-34.jar'
if cache.exists():
    data = cache.read_bytes()
else:
    data = base64.b64decode(urllib.request.urlopen(URL, timeout=120).read())
actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
if actual != BLOB:
    raise SystemExit('AOSP system API jar did not match the pinned Git blob; refusing it.')
cache.parent.mkdir(parents=True, exist_ok=True)
cache.write_bytes(data)
out = ROOT / 'app/libs/android-system-api-34-stubs.jar'
with zipfile.ZipFile(public) as sdk, zipfile.ZipFile(io.BytesIO(data)) as system:
    existing = set(sdk.namelist())
    missing = [n for n in system.namelist() if n.endswith('.class') and n not in existing]
    temporary = out.with_suffix('.jar.tmp')
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as generated:
        for name in sorted(missing):
            item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            generated.writestr(item, system.read(name))
    temporary.replace(out)
print('Verified compile-only system API classes:', len(missing))
print('Output:', out)
