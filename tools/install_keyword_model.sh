#!/usr/bin/env bash
# Pinned official sherpa-onnx int8 keyword model; no training or ambient recordings.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fL --retry 2 -o "$work/model.tar.bz2" https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
printf '%s  %s\n' f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a "$work/model.tar.bz2" | sha256sum -c -
python3 - "$work/model.tar.bz2" <<'MODEL'
import pathlib, sys, tarfile
base = 'sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/'
dest = pathlib.Path('app/src/main/assets/ghost-kws')
dest.mkdir(parents=True, exist_ok=True)
with tarfile.open(sys.argv[1]) as archive:
    for part in ('encoder', 'decoder', 'joiner'):
        name = base + part + '-epoch-12-avg-2-chunk-16-left-64.int8.onnx'
        (dest / (part + '.onnx')).write_bytes(archive.extractfile(name).read())
    (dest / 'tokens.txt').write_bytes(archive.extractfile(base + 'tokens.txt').read())
# Generated with this archive's bpe.model and SentencePiece encode('HEY GHOST').
(dest / 'keywords.txt').write_text('▁HE Y ▁G HO S T @HEY_GHOST\n')
MODEL
