from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which('zsh'), 'zsh is required by the deployment script')
class DeploymentGateTest(unittest.TestCase):
    def test_failed_preflight_prevents_every_deployment_mode_from_touching_device(self):
        source = Path(__file__).parents[1] / 'deploy_system_app.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            deploy = root / source.name
            shutil.copyfile(source, deploy)
            (root / 'check_apk_update.py').write_text('raise SystemExit(1)\n')
            apk = root / 'candidate.apk'
            apk.touch()
            log = root / 'device-calls'
            adb = root / 'adb'
            adb.write_text('#!/bin/sh\nprintf called >> "$DEVICE_LOG"\nexit 99\n')
            adb.chmod(0o700)
            env = dict(os.environ, ADB=str(adb), GHOST_APK=str(apk), DEVICE_LOG=str(log))
            for flags in ([], ['--check'], ['--libs', '--no-backup', '--no-reboot']):
                with self.subTest(flags=flags):
                    result = subprocess.run(['zsh', str(deploy), *flags], env=env,
                                            capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse(log.exists(), 'Device commands ran after a failed preflight')


if __name__ == '__main__':
    unittest.main()
