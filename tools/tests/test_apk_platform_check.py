import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('apk_platform_check', Path(__file__).parents[1] / 'apk_platform_check.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def dex(descriptor):
    encoded = descriptor.encode('ascii')
    data = bytearray(152)
    data[:8] = b'dex\n035\0'
    for offset, value in [(56, 1), (60, 112), (64, 1), (68, 116), (96, 1), (100, 120), (112, 152)]:
        struct.pack_into('<I', data, offset, value)
    return data + bytes([len(encoded)]) + encoded + b'\0'


class PlatformPackagingTest(unittest.TestCase):
    def verify(self, descriptor):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', dex(descriptor))
            check.verify_no_platform_definitions(apk, {
                "Landroid/service/voice/HotwordDetectionService;", "Lcom/android/internal/Example;"})

    def test_app_and_androidx_classes_are_allowed(self):
        self.verify('Lcom/coxy/ghost/platform/GhostHotwordDetectionService;')
        self.verify('Landroidx/core/Example;')
        self.verify('Landroid/support/v4/app/RemoteActionCompatParcelizer;')
        self.verify('Landroid/window/OnBackAnimationCallback;')

    def test_platform_definitions_are_rejected(self):
        for descriptor in ['Landroid/service/voice/HotwordDetectionService;', 'Lcom/android/internal/Example;']:
            with self.assertRaises(ValueError):
                self.verify(descriptor)

    def test_invalid_dex_fails_closed(self):
        with self.assertRaises(ValueError):
            list(check.dex_classes(b'not dex'))
