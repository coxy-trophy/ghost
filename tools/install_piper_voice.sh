#!/bin/zsh
# Fetch Piper's neural voice and put it where GHOST can load it.
#
# Android's own speech engine tops out at clear-but-synthetic. Piper is a neural model that sounds
# like a person and runs entirely on the phone. It needs three things, and the second is the one
# people miss:
#
#   1. the ONNX voice model            (~23 MB int8)
#   2. espeak-ng's pronunciation data  (~1.4 MB pruned)  <- Piper takes phonemes, not text
#   3. sherpa-onnx's Android AAR       (~49 MB, gitignored)
#
# espeak-ng ships 120 language dictionaries totalling 18 MB. English needs one of them, so the rest
# are dropped here rather than carried onto a phone that will never speak Belarusian.
#
# The files land in the app's own filesDir, which root can write to but SELinux will mislabel — a
# file created by root there is readable by root and nobody else, and the app fails to open it with
# no error worth reading. So everything is chowned to the app's uid and restorecon'd afterwards.
# That uid changes whenever the package is reinstalled from scratch, so it is looked up, not
# assumed.
#
# Usage:
#   ./install_piper_voice.sh              # fetch what is missing, prune, push
#   ./install_piper_voice.sh --float      # the 77 MB float model instead of 23 MB int8
#   ./install_piper_voice.sh --aar-only   # just the build dependency, no device needed

set -e

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG=com.coxy.ghost
SCRIPT_DIR="${0:A:h}"
LIBS="$SCRIPT_DIR/../app/libs"
WORK="${TMPDIR:-/tmp}/ghost-piper"

SHERPA_VERSION=1.13.6
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"

VOICE=vits-piper-en_GB-southern_english_female-medium
# en-GB-x-rp — Received Pronunciation, which is what "southern English female" means in the
# espeak voice set the model was trained against.
MODELS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

# espeak-ng files English actually uses. Everything else in that directory is another language.
KEEP=(intonations phondata phondata-manifest phonindex phontab voices lang en_dict)

VARIANT=int8
AAR_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --float) VARIANT=float ;;
    --aar-only) AAR_ONLY=1 ;;
    *) echo "unknown flag: $arg"; exit 1 ;;
  esac
done

mkdir -p "$WORK" "$LIBS"

# --- 1. the build dependency -------------------------------------------------------------------
if [ -f "$LIBS/sherpa-onnx.aar" ]; then
  echo "AAR already present ($(wc -c < "$LIBS/sherpa-onnx.aar" | tr -d ' ') bytes)"
else
  echo "Fetching sherpa-onnx $SHERPA_VERSION..."
  curl -fL --progress-bar -o "$LIBS/sherpa-onnx.aar" "$AAR_URL"
fi
[ "$AAR_ONLY" = "1" ] && { echo "Done (AAR only)."; exit 0; }

# --- 2. the voice ------------------------------------------------------------------------------
if [ "$VARIANT" = "int8" ]; then
  TARBALL="$VOICE-int8.tar.bz2"; EXTRACTED="$VOICE-int8"
else
  TARBALL="$VOICE.tar.bz2"; EXTRACTED="$VOICE"
fi

if [ ! -d "$WORK/$EXTRACTED" ]; then
  echo "Fetching $TARBALL..."
  curl -fL --progress-bar -o "$WORK/$TARBALL" "$MODELS_URL/$TARBALL"
  tar -xjf "$WORK/$TARBALL" -C "$WORK"
fi

SRC="$WORK/$EXTRACTED"
[ -d "$SRC" ] || { echo "extraction produced no $SRC"; exit 1; }

# --- 3. prune espeak to English ------------------------------------------------------------------
STAGE="$WORK/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE/espeak-ng-data"
cp "$SRC"/*.onnx "$STAGE/voice.onnx"
cp "$SRC"/tokens.txt "$STAGE/tokens.txt"
for f in "${KEEP[@]}"; do
  [ -e "$SRC/espeak-ng-data/$f" ] && cp -R "$SRC/espeak-ng-data/$f" "$STAGE/espeak-ng-data/"
done
echo "Staged $(du -sh "$STAGE" | awk '{print $1}') (espeak pruned from $(du -sh "$SRC/espeak-ng-data" | awk '{print $1}'))"

# --- 4. onto the phone ---------------------------------------------------------------------------
$ADB get-state >/dev/null 2>&1 || { echo "No device attached."; exit 1; }

UID_NAME=$($ADB shell "su -c 'stat -c %U /data/data/$PKG'" | tr -d '\r')
[ -n "$UID_NAME" ] || { echo "Could not read the app's uid — is it installed?"; exit 1; }
echo "App uid: $UID_NAME"

echo "Pushing..."
$ADB shell "rm -rf /data/local/tmp/ghost-piper" >/dev/null 2>&1 || true
$ADB push "$STAGE" /data/local/tmp/ghost-piper >/dev/null

DEST="/data/data/$PKG/files/piper"
$ADB shell "su -c 'rm -rf $DEST && mkdir -p $DEST && cp -R /data/local/tmp/ghost-piper/. $DEST && \
  chown -R $UID_NAME:$UID_NAME $DEST && \
  chmod -R u+rwX $DEST && \
  restorecon -R $DEST'"
$ADB shell "rm -rf /data/local/tmp/ghost-piper" >/dev/null 2>&1 || true

echo
echo "Installed:"
$ADB shell "su -c 'ls -la $DEST'" | tr -d '\r'
echo
echo "Restart GHOST and pick Piper under System -> Her voice."
