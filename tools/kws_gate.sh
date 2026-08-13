#!/bin/zsh
# Phase 5.2 live gate: measure the shipped wake-word model against a real human voice, then tune.
#
# The gate has been open since 2026-08-06 with no instrument attached. "Hey Ghost didn't wake it"
# has two causes with opposite fixes, and this script tells them apart:
#
#   peak ~0.9, never fired   -> threshold/debounce problem. `./kws_gate.sh tune 0.6` and retest.
#   peak ~0.05               -> the model has never heard this voice. The shipped hey_ghost.tflite
#                               is 100% macOS `say` synthesis, so this is the likely answer, and it
#                               means collecting real clips and retraining. See `record` below.
#
# Everything runs through GHOST itself, so clips are captured and scored on the same AudioRecord/HAL
# path the live detector listens on. Recording with tinycap or scoring on the Mac would measure a
# different signal than the one that actually has to work.
#
# Usage:
#   ./kws_gate.sh record positive 20   # say "Hey Ghost" once per clip, 20 clips
#   ./kws_gate.sh record negative 10 30 # 10 x 30s of normal speech/room noise, NO wake word
#   ./kws_gate.sh score positive       # replay clips through the model, print peak + hit rates
#   ./kws_gate.sh tune 0.60 3          # set threshold + consecutive-frame requirement, live
#   ./kws_gate.sh watch                # tail the detector's own probability logging
#   ./kws_gate.sh pull                 # pull clips to tools/kws_data/real/ for retraining
#
# A full first pass:
#   ./kws_gate.sh record positive 20 && ./kws_gate.sh score positive

set -e
# zsh aborts on a glob that matches nothing, so `mv dir/*.wav` fails the script outright the first
# time a label has no clips yet — which is exactly the starting state. Make empty globs expand to
# nothing instead, the way sh behaves.
setopt NULL_GLOB

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG=com.coxy.ghost
SVC="$PKG/.GhostService"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/kws"
SCRIPT_DIR="${0:A:h}"
CMD="${1:-help}"

svc() {
  # Service actions need root: GHOST runs as a priv-app under SYSTEM_UID and the adb shell uid
  # cannot start its service directly.
  $ADB shell "su -c 'am startservice $* -n $SVC'" >/dev/null 2>&1
}

require_running() {
  if ! $ADB shell "pidof $PKG" >/dev/null 2>&1; then
    echo "GHOST is not running. Start it with:"
    echo "  $ADB shell \"su -c 'am start-foreground-service -n $SVC'\""
    exit 1
  fi
}

case "$CMD" in

record)
  LABEL="${2:-positive}"
  COUNT="${3:-20}"
  SECS="${4:-3}"
  [[ "$LABEL" == "positive" || "$LABEL" == "negative" ]] || { echo "label must be positive|negative"; exit 1; }
  require_running

  echo "Recording $COUNT x ${SECS}s '$LABEL' clips."
  if [ "$LABEL" = "positive" ]; then
    echo "Say \"Hey Ghost\" ONCE per clip. Vary it deliberately — the model has to generalise:"
    echo "  distance (arm's length, across the room), volume (normal, quiet, half-whisper),"
    echo "  speed (natural, fast, drawn out), and angle (facing it, turned away, phone in pocket)."
  else
    echo "Talk normally WITHOUT saying \"Hey Ghost\" — conversation, TV, music, silence, room tone."
    echo "These are the hard negatives that stop it firing at nothing."
  fi
  echo

  for i in $(seq 1 "$COUNT"); do
    printf "[%2d/%2d] ready... " "$i" "$COUNT"
    sleep 1
    printf "SPEAK (%ss) " "$SECS"
    svc "-a com.coxy.ghost.action.CAPTURE_KWS --es label $LABEL --ei seconds $SECS"
    sleep $((SECS + 3))   # recording + mic handover on both ends
    printf "ok\n"
  done

  echo
  echo "On device: $($ADB shell "ls $DEVICE_DIR 2>/dev/null | grep -c ${LABEL}_" | tr -d '\r') '$LABEL' clips"
  echo "Next: ./kws_gate.sh score $LABEL"
  ;;

score)
  LABEL="${2:-positive}"
  require_running
  echo "Scoring '$LABEL' clips through the shipped model..."
  $ADB logcat -c
  svc "-a com.coxy.ghost.action.SCORE_KWS --es label $LABEL"
  sleep 6
  $ADB logcat -d -s KwsScorer:I | sed 's/^.*KwsScorer *: //'
  echo
  echo "Reading it: a positive clip that peaks below ~0.5 means the model does not recognise this"
  echo "voice and needs retraining on real clips (./kws_gate.sh pull, then train_hey_ghost_kws.py)."
  echo "A peak well above the hit-rate knee means you can just lower the threshold: ./kws_gate.sh tune <t>"
  ;;

tune)
  THRESHOLD="${2:-}"
  FRAMES="${3:-}"
  [ -n "$THRESHOLD" ] || { echo "usage: ./kws_gate.sh tune <threshold 0.05-0.999> [frames 1-40]"; exit 1; }
  require_running
  ARGS="-a com.coxy.ghost.action.SET_KWS_TUNING --ef threshold $THRESHOLD"
  [ -n "$FRAMES" ] && ARGS="$ARGS --ei frames $FRAMES"
  svc "$ARGS"
  sleep 1
  $ADB logcat -d -s MicListener:I | grep -i "wake tuning applied" | tail -1 | sed 's/^.*MicListener *: //'
  echo "Applied live — no rebuild, no reboot. Now say \"Hey Ghost\" and watch:"
  echo "  ./kws_gate.sh watch"
  ;;

watch)
  echo "Tailing the detector. Say \"Hey Ghost\" — you should see peaks rise, then a trigger."
  echo "  'wake peak over last 10s'  = the model's best score, even when nothing fired"
  echo "  'wake near-miss'           = crossed 60% of threshold but not threshold"
  echo "  'wake-word triggered'      = fired, mic handed to the recognizer"
  echo "  'heard (wake)'             = the command that came out of it"
  echo "Ctrl-C to stop."
  echo
  $ADB logcat -c
  $ADB logcat -s MicListener:D GhostService:I KwsScorer:I
  ;;

pull)
  POS_DIR="$SCRIPT_DIR/kws_data/real/positives"
  NEG_DIR="$SCRIPT_DIR/kws_data/real/negatives"
  mkdir -p "$POS_DIR" "$NEG_DIR"
  TMP=$(mktemp -d)
  # Pull file-by-file rather than pulling the directory: `adb pull <dir> <dest>` puts the contents
  # at <dest> only when <dest> does not already exist, and nests them under <dest>/kws when it does.
  for f in $($ADB shell "ls $DEVICE_DIR/*.wav 2>/dev/null" | tr -d '\r'); do
    $ADB pull "$f" "$TMP/" >/dev/null 2>&1 || true
  done
  mv -f "$TMP"/positive_*.wav "$POS_DIR"/ 2>/dev/null || true
  mv -f "$TMP"/negative_*.wav "$NEG_DIR"/ 2>/dev/null || true
  rm -rf "$TMP"
  $ADB shell "rm -f $DEVICE_DIR/*.wav" >/dev/null 2>&1 || true
  echo "positives: $(find "$POS_DIR" -name '*.wav' | wc -l | tr -d ' ')"
  echo "negatives: $(find "$NEG_DIR" -name '*.wav' | wc -l | tr -d ' ')"
  echo "Next: python3 $SCRIPT_DIR/train_hey_ghost_kws.py --real-data $SCRIPT_DIR/kws_data/real"
  ;;

*)
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  ;;
esac
