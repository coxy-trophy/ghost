#!/usr/bin/env python3
"""Build and sign a GHOST release with a persistent local key; never installs it."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile

from apk_platform_check import verify_no_platform_definitions
from check_apk_update import metadata, toolchain

ROOT = Path(__file__).resolve().parents[1]


def create_key(directory, env):
    if directory.exists():
        raise ValueError('Key directory already exists; refusing to replace signing identity')
    directory.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.ghost-signing-', dir=directory.parent) as temp:
        staging = Path(temp)
        password = staging / 'password.txt'
        password.write_text(secrets.token_urlsafe(48) + '\n')
        password.chmod(0o600)
        keytool = shutil.which('keytool', path=env.get('PATH'))
        if keytool is None:
            raise ValueError('keytool missing: configure a full JDK')
        subprocess.run([str(keytool), '-genkeypair', '-noprompt', '-storetype', 'PKCS12',
                        '-keystore', str(staging / 'release.p12'), '-alias', 'ghost-release',
                        '-storepass:file', str(password), '-keypass:file', str(password),
                        '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
                        '-validity', '10000', '-dname', 'CN=GHOST personal release,O=GHOST'],
                       env=env, check=True, capture_output=True, timeout=60)
        (staging / 'release.p12').chmod(0o600)
        # Rename the complete private directory, never publish a half-generated identity.
        os.rename(staging, directory)
    print('Created persistent signing identity at ' + str(directory), flush=True)
    print('Back up this private directory securely; it is not the original installed app key.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key-dir', type=Path, default=Path.home() / '.local/share/ghost/signing')
    parser.add_argument('--create-key', action='store_true', help='Create a new identity only if the key directory is absent')
    parser.add_argument('--sdk')
    args = parser.parse_args()
    try:
        aapt, signer, env = toolchain(args.sdk)
        env['ANDROID_HOME'] = str(aapt.parent.parent.parent)
        directory = args.key_dir.expanduser().resolve()
        if args.create_key and not directory.exists():
            create_key(directory, env)
        key = directory / 'release.p12'
        password = directory / 'password.txt'
        if not key.is_file() or not password.is_file():
            raise ValueError('Signing key missing. Use --create-key for a new identity or restore the complete key directory.')
        if os.name == 'posix' and any(p.stat().st_mode & 0o077 for p in (directory, key, password)):
            raise ValueError('Signing directory and files must be private (directory 700, files 600)')
        subprocess.run([str(ROOT / 'gradlew'), ':app:assembleRelease', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process'],
                       cwd=ROOT, env=env, check=True)
        mapping = (ROOT / 'app/build/outputs/mapping/release/mapping.txt').read_text()
        for name in ('com.coxy.ghost.perception.SamplesCallback',
                     'com.k2fsa.sherpa.onnx.KeywordSpotterConfig',
                     'com.arm.aichat.internal.InferenceEngineImpl'):
            if f'{name} -> {name}:' not in mapping:
                raise ValueError('Release renamed or removed a required JNI class: ' + name)
        seeds = (ROOT / 'app/build/outputs/mapping/release/seeds.txt').read_text()
        if 'com.coxy.ghost.perception.SamplesCallback: java.lang.Integer invoke(float[])' not in seeds:
            raise ValueError('Release did not retain the boxed JNI audio callback')
        unsigned = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
        output = ROOT / 'app/build/outputs/verified-release'
        output.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.sign-', dir=output) as temp:
            aligned = Path(temp) / 'aligned.apk'
            signed = Path(temp) / 'ghost-release.apk'
            subprocess.run([str(aapt.parent / 'zipalign'), '-P', '16', '-f', '4', str(unsigned), str(aligned)], check=True)
            # PKCS12 uses the store password for its key. apksigner consumes password files
            # line-by-line, so passing the same one-line file twice would fail on the key read.
            subprocess.run([str(signer), 'sign', '--ks', str(key), '--ks-key-alias', 'ghost-release',
                            '--ks-pass', 'file:' + str(password),
                            '--out', str(signed), str(aligned)], env=env, check=True, capture_output=True)
            info = metadata(signed, aapt, signer, env)
            verify_no_platform_definitions(signed)
            subprocess.run([str(aapt.parent / 'zipalign'), '-c', '-P', '16', '4', str(signed)], check=True)
            name = f"ghost-{info['version_name']}-{info['version_code']}.apk"
            os.replace(signed, output / name)
            (output / (name + '.json')).write_text(json.dumps(info, indent=2) + '\n')
            (output / (name + '.sha256')).write_text(info['sha256'] + '  ' + name + '\n')
        print('Verified signed APK: ' + str(output / name))
        print('Certificate SHA-256: ' + ', '.join(info['certificates']))
        print('Run tools/check_apk_update.py before any deployment. No device changes made.')
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        print('Release packaging failed: ' + (str(exc) if not isinstance(exc, subprocess.CalledProcessError)
                                              else 'build/signing tool failed; no deployment performed'), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
