#!/bin/zsh
# Record real "Hey Ghost" wake-word training clips through the phone's own mic.
#
# Why this exists: the Phase 5.2 detector was trained entirely on macOS `say` synthesis for
# positives and the mini_speech_commands dataset for negatives. Neither resembles what the
# detector actually sees at runtime — 16 kHz mono audio off this phone's HAL, in this room.
# These recordings close that gap.
#
# Clips are captured by GHOST itself (GhostService ACTION_CAPTURE_KWS -> KwsSampleRecorder), so
# they come through the same AudioRecord path the detector runs on. `tinycap` would read raw ALSA
# instead, which is the wrong signal to train on.
#
# Usage:
#   ./record_kws_samples.sh positive 40      # 40 clips, say "Hey Ghost" once per clip
#   ./record_kws_samples.sh negative 10 30   # 10 clips of 30s ambient/other speech (hard negatives)
#
# Output lands in tools/kws_data/real/{positives,negatives}/ , which train_hey_ghost_kws.py picks up.

set -e
# zsh aborts on a glob matching nothing; the first run of a label has no clips yet.
setopt NULL_GLOB

LABEL="${1:-positive}"
COUNT="${2:-20}"
SECONDS_PER="${3:-3}"

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG=com.coxy.ghost
DEVICE_DIR="/sdcard/Android/data/$PKG/files/kws"
SCRIPT_DIR="${0:A:h}"

case "$LABEL" in
  positive) OUT_DIR="$SCRIPT_DIR/kws_data/real/positives" ;;
  negative) OUT_DIR="$SCRIPT_DIR/kws_data/real/negatives" ;;
  *) echo "label must be 'positive' or 'negative'"; exit 1 ;;
esac
mkdir -p "$OUT_DIR"

if ! $ADB shell "pidof $PKG" >/dev/null 2>&1; then
  echo "GHOST is not running. Start it first:"
  echo "  $ADB shell \"su -c 'am start-foreground-service -n $PKG/.GhostService'\""
  exit 1
fi

echo "Recording $COUNT x ${SECONDS_PER}s '$LABEL' clips into $OUT_DIR"
if [ "$LABEL" = "positive" ]; then
  echo "Say \"Hey Ghost\" ONCE per clip — vary distance, volume, speed, and where you are in the room."
  echo "Some clips should be deliberately awkward: quiet, fast, turned away, with background noise."
else
  echo "Talk normally WITHOUT saying \"Hey Ghost\" — conversation, TV, music, silence, room noise."
  echo "These are the hard negatives; the current model has never heard this room."
fi
echo

for i in $(seq 1 "$COUNT"); do
  printf "[%2d/%2d] " "$i" "$COUNT"
  printf "get ready... "
  sleep 1
  printf "RECORDING (%ss) " "$SECONDS_PER"

  $ADB shell "su -c 'am startservice -a com.coxy.ghost.action.CAPTURE_KWS \
    --es label $LABEL --ei seconds $SECONDS_PER -n $PKG/.GhostService'" >/dev/null 2>&1

  # Recording time + mic handover on both ends.
  sleep $((SECONDS_PER + 3))
  printf "done\n"
done

echo
echo "Pulling clips..."
# Pull file-by-file. `adb pull <dir> <dest>` places the contents at <dest> only when <dest> does
# not exist, and nests them under <dest>/kws when it does — so the directory form worked only as
# long as the rmdir below had succeeded on the previous run.
PULL_TMP=$(mktemp -d)
for f in $($ADB shell "ls $DEVICE_DIR/*.wav 2>/dev/null" | tr -d '\r'); do
  $ADB pull "$f" "$PULL_TMP/" >/dev/null 2>&1 || true
done
mv -f "$PULL_TMP"/*.wav "$OUT_DIR"/ 2>/dev/null || true
rm -rf "$PULL_TMP"

# Clear the device-side staging dir so the next run only pulls new clips.
$ADB shell "rm -f $DEVICE_DIR/*.wav" >/dev/null 2>&1 || true

echo "Total '$LABEL' clips on disk: $(find "$OUT_DIR" -name '*.wav' | wc -l | tr -d ' ')"
echo "Next: python3 tools/train_hey_ghost_kws.py --real-data tools/kws_data/real"
