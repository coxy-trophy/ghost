import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('check_apk', Path(__file__).parents[1] / 'check_apk_update.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class UpdateCheckTest(unittest.TestCase):
    def artifact(self, version=65, certificate='a', package=check.PACKAGE):
        return dict(version_code=version, certificates=[certificate], package=package)

    def test_same_signature_and_upgrade_allowed(self):
        self.assertEqual([], check.compare(self.artifact(66), self.artifact()))

    def test_new_private_key_is_not_a_replacement_for_original(self):
        errors = check.compare(self.artifact(66, 'new'), self.artifact(certificate='original'))
        self.assertTrue(any('Signing certificates differ' in e for e in errors))

    def test_wrong_package_and_downgrade_rejected(self):
        errors = check.compare(self.artifact(64, package='com.example'), self.artifact())
        self.assertEqual(2, len(errors))

    def test_unsigned_apk_fails_before_metadata_is_trusted(self):
        with patch.object(check, 'run', side_effect=subprocess.CalledProcessError(1, ['apksigner'])) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                check.metadata('/unused.apk', 'aapt2', 'apksigner', {})
            self.assertEqual(1, run.call_count)

    def test_no_verified_certificate_fails_closed(self):
        with patch.object(check, 'run', return_value='No verified signers'):
            with self.assertRaisesRegex(ValueError, 'no verified signer'):
                check.metadata('/unused.apk', 'aapt2', 'apksigner', {})


if __name__ == '__main__':
    unittest.main()
