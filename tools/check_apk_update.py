#!/usr/bin/env python3
"""Read-only APK update check. Never installs, stops an app, or changes device data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

PACKAGE = 'com.coxy.ghost'


def run(args, env=None):
    return subprocess.run([str(a) for a in args], check=True, capture_output=True,
                          text=True, env=env, timeout=120).stdout


def metadata(apk, aapt, signer, env):
    # Verification must succeed before trusting any reported signer.
    verification = run([signer, 'verify', '--print-certs', apk], env)
    certs = sorted(set(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$',
                                  verification, re.MULTILINE)))
    if not certs:
        raise ValueError('APK has no verified signer certificates')
    info = run([aapt, 'dump', 'badging', apk], env)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", info, re.MULTILINE)
    if package is None:
        raise ValueError('Cannot read APK package/version metadata')
    digest = hashlib.sha256()
    with open(apk, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return dict(package=package[1], version_code=int(package[2]), version_name=package[3],
                certificates=[c.lower() for c in certs], sha256=digest.hexdigest())


def compare(candidate, installed):
    errors = []
    if candidate['package'] != PACKAGE or installed['package'] != PACKAGE:
        errors.append('Both APKs must belong to ' + PACKAGE)
    if candidate['certificates'] != installed['certificates']:
        errors.append('Signing certificates differ. The original private signing key is required; '
                      'do not uninstall or overwrite the system APK to bypass this check.')
    if candidate['version_code'] < installed['version_code']:
        errors.append('Candidate version is older than the installed version')
    return errors


def toolchain(sdk):
    env = os.environ.copy()
    java_roots = [env.get('JAVA_HOME', ''), str(Path.home() / 'tools/jdk17'),
                  str(Path.home() / '.local/share/jdk-17')]
    for root in java_roots:
        if root and (Path(root) / 'bin/java').is_file():
            env['JAVA_HOME'] = root
            env['PATH'] = str(Path(root) / 'bin') + os.pathsep + env.get('PATH', '')
            break
    if not shutil.which('java', path=env.get('PATH')):
        raise ValueError('Java is missing: set JAVA_HOME to your JDK')
    roots = [Path(sdk)] if sdk else [Path(p) for p in (
        env.get('ANDROID_HOME'), env.get('ANDROID_SDK_ROOT'),
        str(Path.home() / 'tools/android-sdk'), str(Path.home() / 'Android/Sdk'),
        str(Path.home() / 'Library/Android/sdk')) if p]
    for root in roots:
        versions = sorted((root / 'build-tools').glob('*'),
                          key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)), reverse=True)
        for version in versions:
            if (version / 'aapt2').is_file() and (version / 'apksigner').is_file():
                return version / 'aapt2', version / 'apksigner', env
    raise ValueError('Android build-tools missing: set ANDROID_HOME or --sdk')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--installed-apk', type=Path, help='Compare with a local backup instead of reading the device')
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial', help='ADB device serial (ANDROID_SERIAL is also respected)')
    parser.add_argument('--sdk')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    try:
        aapt, signer, env = toolchain(args.sdk)
        candidate = metadata(args.apk, aapt, signer, env)
        with tempfile.TemporaryDirectory(prefix='ghost-update-check-') as temp:
            installed_apk = args.installed_apk
            if installed_apk is None:
                adb = [args.adb] + (['-s', args.serial] if args.serial else [])
                paths = run(adb + ['shell', 'pm', 'path', PACKAGE], env).splitlines()
                paths = [line.removeprefix('package:').strip() for line in paths if line.startswith('package:')]
                if len(paths) != 1:
                    raise ValueError('Expected one installed Ghost APK; absent/split installations require separate review')
                installed_apk = Path(temp) / 'installed.apk'
                # adb pull is a read-only operation on the phone. Paths are structured arguments.
                run(adb + ['pull', paths[0], installed_apk], env)
            installed = metadata(installed_apk, aapt, signer, env)
        errors = compare(candidate, installed)
        report = dict(compatible=not errors, candidate=candidate, installed=installed, errors=errors)
        if args.json:
            print(json.dumps(report, indent=2))
        else:
            print(f"Candidate: {candidate['version_name']} ({candidate['version_code']})")
            print(f"Installed: {installed['version_name']} ({installed['version_code']})")
            print('Candidate certificate: ' + ', '.join(candidate['certificates']))
            print('Installed certificate: ' + ', '.join(installed['certificates']))
            if errors:
                print('\n'.join('BLOCKED: ' + error for error in errors))
            else:
                print('PASS: verified signatures, matching package/certificates and nondecreasing version.')
                print('This does not install the APK or establish runtime/data-migration compatibility.')
        return 1 if errors else 0
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        # Do not expose arbitrary command output or potentially sensitive APK contents.
        print('Update check failed: ' + (str(exc) if not isinstance(exc, subprocess.CalledProcessError)
                                        else 'APK/device tool returned a failure; no deployment performed'), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
