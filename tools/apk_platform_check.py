"""Reject APKs that accidentally package compile-only Android platform definitions."""
import re
from pathlib import Path
import struct
import zipfile


def dex_classes(data):
    if not data.startswith(b'dex\n') or len(data) < 112:
        raise ValueError('Invalid dex header')
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    string_count, strings = u32(56), u32(60)
    type_count, types = u32(64), u32(68)
    class_count, classes = u32(96), u32(100)
    for count, offset, size in ((string_count, strings, 4), (type_count, types, 4), (class_count, classes, 32)):
        if offset + count * size > len(data):
            raise ValueError('Invalid dex table')
    for i in range(class_count):
        type_index = u32(classes + 32 * i)
        if type_index >= type_count:
            raise ValueError('Invalid dex class type')
        string_index = u32(types + 4 * type_index)
        if string_index >= string_count:
            raise ValueError('Invalid dex descriptor index')
        offset = u32(strings + 4 * string_index)
        # Skip the string's ULEB128 UTF-16 length (descriptor itself is ASCII).
        for _ in range(5):
            byte = data[offset]; offset += 1
            if byte < 128:
                break
        else:
            raise ValueError('Invalid dex string length')
        end = data.index(0, offset)
        yield data[offset:end].decode('ascii')


def verify_no_platform_definitions(apk, forbidden=None):
    if forbidden is None:
        stubs = Path(__file__).resolve().parent.parent / 'app/libs/android-system-api-34-stubs.jar'
        with zipfile.ZipFile(stubs) as archive:
            forbidden = {'L' + name[:-6] + ';' for name in archive.namelist() if name.endswith('.class')}
    with zipfile.ZipFile(apk) as archive:
        dex = [name for name in archive.namelist() if re.fullmatch(r'classes(?:\d+)?\.dex', name)]
        if not dex:
            raise ValueError('APK contains no dex')
        for name in dex:
            for descriptor in dex_classes(archive.read(name)):
                # Match only the actual compile-only jar, allowing D8 compatibility shims.
                if descriptor in forbidden:
                    raise ValueError('Compile-only platform definition leaked into APK: ' + descriptor)
