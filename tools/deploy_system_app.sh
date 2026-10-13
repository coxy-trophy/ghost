#!/bin/zsh
# Deploy a freshly built GHOST APK into the Magisk module that magic-mounts it as a priv-app.
#
# GHOST runs from /system/priv-app/GHOST (SYSTEM|PRIVILEGED), which is what grants the permanent
# FGS-start allowlist a normal app never gets. The root fs is erofs and read-only, so "install"
# means: replace the APK inside the Magisk module, then reboot so the module remounts it and
# PackageManager rescans.
#
# The module's lib/arm64/ is a separate copy from the APK's, so it only changes when told to
# (--libs re-extracts it). Leaving it alone is right for a pure Kotlin change against unchanged
# native code, and wrong the moment a dependency brings native code with it.
#
# That distinction is not "did :lib change". Adding the sherpa-onnx AAR on 2026-08-23 was a
# Kotlin-only change by that test, and it added four .so files — libonnxruntime.so and three
# libsherpa-onnx-*.so. Deploying without --libs would have installed a Piper voice whose JNI
# library simply was not there. Compare before deciding:
#
#   unzip -l app/build/outputs/apk/debug/app-debug.apk 'lib/arm64-v8a/*' | grep -c '\.so$'
#   adb shell "su -c 'ls /data/adb/modules/ghost_systemapp/system/priv-app/GHOST/lib/arm64/*.so | wc -l'"
#
# If the counts differ, --libs is required. --libs is also always safe: it re-extracts every lib
# from the APK, so it can only ever be a superset of what is there.
#
# Replacing the priv-app APK can reset /data/data/com.coxy.ghost entirely. Observed 2026-08-12: a
# deploy wiped the Ollama API key, the persisted model path, and the whole memory DB (turns,
# extracted facts, personality profile + snapshots, cloud interaction log). So this script backs
# that data up to the Mac BEFORE it touches the module. The GGUF itself is not backed up — it is
# ~1 GB and re-copyable from /sdcard/Download — but the path preference is, so a restore only needs
# the model file put back.
#
# To restore a backup:
#   tar -xzf ghost-appdata-<stamp>.tar.gz -C /tmp/restore
#   adb push /tmp/restore/. /data/local/tmp/ghost-restore/
#   adb shell "su -c 'cp -a /data/local/tmp/ghost-restore/. /data/data/com.coxy.ghost/ && \
#     chown -R u0_a221:u0_a221 /data/data/com.coxy.ghost && \
#     restorecon -R /data/data/com.coxy.ghost'"
# then re-stage the model with ACTION_SET_MODEL_PATH and reboot. Check the uid first — it changes.
#
# Usage:
#   ./deploy_system_app.sh                # back up app data, replace the APK, then reboot
#   ./deploy_system_app.sh --check        # read-only package/version/signature check, then exit
#   ./deploy_system_app.sh --libs         # also re-extract arm64 native libs from the APK
#   ./deploy_system_app.sh --no-reboot    # stage only; PM will not see the change until a reboot
#   ./deploy_system_app.sh --no-backup    # skip the app-data backup (not recommended)

set -e

if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB=adb
  else
    ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
  fi
fi
PKG=com.coxy.ghost
SCRIPT_DIR="${0:A:h}"
APK="${GHOST_APK:-$SCRIPT_DIR/../app/build/outputs/apk/debug/app-debug.apk}"
MODULE=/data/adb/modules/ghost_systemapp/system/priv-app/GHOST
# Staged through /data/local/tmp rather than /sdcard/Download.
#
# /sdcard is a symlink into /storage/emulated/0, which is a FUSE view that is not always mounted in
# the shell's namespace — on 2026-08-19 it was an empty stub while the real backing store at
# /data/media/0 was perfectly intact, and every push failed with `secure_mkdirs() failed`. There is
# no reason to route a root-only deploy through emulated storage at all; /data/local/tmp is always
# present, always writable by adb, and never depends on a user being unlocked.
STAGE=/data/local/tmp/GHOST-new.apk

BACKUP_DIR="${GHOST_BACKUP_DIR:-$HOME/ghost-backups/appdata}"

CHECK_ONLY=0
DO_LIBS=0
DO_REBOOT=1
DO_BACKUP=1
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --libs) DO_LIBS=1 ;;
    --no-reboot) DO_REBOOT=0 ;;
    --no-backup) DO_BACKUP=0 ;;
    *) echo "unknown flag: $arg"; exit 1 ;;
  esac
done

[ -f "$APK" ] || { echo "No APK at $APK — run ./gradlew :app:assembleDebug first"; exit 1; }

# Verify compatibility BEFORE backups, pushes, force-stop, module writes or reboot.
python3 "$SCRIPT_DIR/check_apk_update.py" --apk "$APK" --adb "$ADB"
[ "$CHECK_ONLY" = "1" ] && exit 0

echo "Local APK:  $APK ($(wc -c < "$APK" | tr -d ' ') bytes)"
LOCAL_MD5=$(python3 -c 'import hashlib,sys; h=hashlib.md5(); f=open(sys.argv[1],"rb"); [h.update(b) for b in iter(lambda:f.read(1048576),b"")]; print(h.hexdigest())' "$APK")
echo "Local md5:  $LOCAL_MD5"
echo "Installed:  $($ADB shell "dumpsys package $PKG | grep -E 'versionCode|versionName' | head -2" | tr -d '\r' | tr '\n' ' ')"
echo

# --- 0a. Refuse to ship an APK whose native libs the module does not have ---
#
# The failure this prevents is silent: the APK installs, the app starts, and one feature dies at
# the moment it first calls into a .so that was never copied. Counting is enough to catch it —
# a new dependency with native code always changes the count.
APK_LIBS=$(unzip -l "$APK" 'lib/arm64-v8a/*' 2>/dev/null | grep -c '\.so$')
MOD_LIBS=$($ADB shell "su -c 'ls $MODULE/lib/arm64/*.so 2>/dev/null | wc -l'" | tr -d '\r' | tr -d ' ')
if [ "$APK_LIBS" != "$MOD_LIBS" ]; then
  echo "Native libs: APK has $APK_LIBS, module has $MOD_LIBS."
  if [ "$DO_LIBS" = "0" ]; then
    echo
    echo "The module is missing native libraries this APK expects. Deploying now would install an"
    echo "app that starts fine and then fails the first time it touches the missing library."
    echo "Re-run with --libs."
    exit 1
  fi
  echo "  --libs given, so they will be replaced."
else
  echo "Native libs: $APK_LIBS in both."
fi
echo

# --- 0b. Back up app data BEFORE touching the module (see the header note) ---
if [ "$DO_BACKUP" = "1" ]; then
  echo "Backing up app data..."
  BK_PATHS=""
  for p in databases shared_prefs files/datastore; do
    if $ADB shell "su -c '[ -e /data/data/$PKG/$p ] && echo yes'" 2>/dev/null | tr -d '\r' | grep -q yes; then
      BK_PATHS="$BK_PATHS $p"
    fi
  done
  if [ -z "$BK_PATHS" ]; then
    echo "  nothing to back up — no databases/, shared_prefs/ or files/datastore/ on device"
  else
    STAMP=$(date +%Y%m%d-%H%M%S)
    DEVTAR="/data/local/tmp/ghost-appdata-$STAMP.tar.gz"
    OUT="$BACKUP_DIR/ghost-appdata-$STAMP.tar.gz"
    mkdir -p "$BACKUP_DIR"
    $ADB shell "su -c 'cd /data/data/$PKG && tar -czf $DEVTAR$BK_PATHS && chmod 644 $DEVTAR'" >/dev/null
    $ADB pull "$DEVTAR" "$OUT" >/dev/null
    $ADB shell "su -c 'rm -f $DEVTAR'" >/dev/null
    [ -s "$OUT" ] || { echo "Backup came back empty — refusing to deploy over live data."; exit 1; }
    echo "  saved $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes), contents:$BK_PATHS"
  fi
  echo
fi

echo "Pushing to device..."
$ADB push "$APK" "$STAGE" >/dev/null

# Stop GHOST before the APK underneath it is overwritten.
#
# The module file is magic-mounted at /system/priv-app/GHOST/GHOST.apk, so a running process has
# this exact file mapped — along with the 1.1 GB GGUF it has open. Copying over it invalidates
# those mappings, and the next page the process touches raises SIGBUS/BUS_ADRERR. That produced one
# tombstone per deploy on 2026-08-19 (tombstone_20/21/22, all com.coxy.ghost, all BUS_ADRERR at a
# deploy reboot); the plain reboot immediately afterwards, with no APK swap, produced none.
#
# The kill-switch flag is deliberately untouched: this stops the process, it does not tell GHOST to
# stay down, so service.sh brings it back on the next boot as usual.
echo "Stopping GHOST before replacing its APK..."
$ADB shell "su -c 'am force-stop $PKG'" >/dev/null 2>&1 || true
sleep 2

echo "Replacing module APK..."
$ADB shell "su -c 'cp $STAGE $MODULE/GHOST.apk && \
  chown root:root $MODULE/GHOST.apk && \
  chmod 644 $MODULE/GHOST.apk && \
  chcon u:object_r:system_file:s0 $MODULE/GHOST.apk'"

DEVICE_MD5=$($ADB shell "su -c 'md5sum $MODULE/GHOST.apk'" | tr -d '\r' | awk '{print $1}')
echo "Device md5: $DEVICE_MD5"
if [ "$LOCAL_MD5" != "$DEVICE_MD5" ]; then
  echo "MD5 MISMATCH — the copy did not land correctly. Not rebooting."
  exit 1
fi
echo "Checksums match."

if [ "$DO_LIBS" = "1" ]; then
  echo "Re-extracting arm64 native libs..."
  TMP=$(mktemp -d)
  unzip -o -q "$APK" 'lib/arm64-v8a/*' -d "$TMP"
  $ADB shell "su -c 'rm -f $MODULE/lib/arm64/*.so'"
  for so in "$TMP"/lib/arm64-v8a/*.so; do
    $ADB push "$so" "/data/local/tmp/$(basename "$so")" >/dev/null
    $ADB shell "su -c 'mv /data/local/tmp/$(basename "$so") $MODULE/lib/arm64/ && \
      chown root:root $MODULE/lib/arm64/$(basename "$so") && \
      chmod 644 $MODULE/lib/arm64/$(basename "$so") && \
      chcon u:object_r:system_file:s0 $MODULE/lib/arm64/$(basename "$so")'"
  done
  rm -rf "$TMP"
  echo "Native libs replaced."
fi

# --- 4. Stamp the carried GHOST version onto the module ---
#
# Magisk Manager shows module.prop's version line, and until now it said v0.3.0 while the module
# held GHOST 0.27.1 — a number that had been wrong for twenty-four app releases because nothing
# ever wrote it. Writing a fresh number by hand only resets the clock on the same problem, so the
# app version is stamped here instead, from the APK actually being shipped rather than from
# build.gradle.kts, which can disagree with the last build.
#
# The base "vX.Y.Z" is the module's own version and is preserved; everything after it is replaced,
# so re-deploying restamps rather than accumulating.
MODULE_PROP=/data/adb/modules/ghost_systemapp/module.prop
AAPT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/aapt2 2>/dev/null | tail -1)
if [ -n "$AAPT" ] && [ -f "$AAPT" ]; then
  APP_VER=$("$AAPT" dump badging "$APK" 2>/dev/null | \
    sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
fi
if [ -n "$APP_VER" ]; then
  $ADB shell "su -c 'sed -i -E \"s|^version=(v[0-9]+\.[0-9]+\.[0-9]+).*|version=\\1 (GHOST $APP_VER)|\" $MODULE_PROP'"
  echo "Module version: $($ADB shell "su -c 'grep ^version= $MODULE_PROP'" | tr -d '\r')"
else
  echo "Could not read the APK's version (no aapt2) — module.prop left unstamped."
fi

$ADB shell "rm -f $STAGE" >/dev/null 2>&1 || true

if [ "$DO_REBOOT" = "0" ]; then
  echo "Staged. Reboot to apply."
  exit 0
fi

echo "Rebooting..."
$ADB reboot
$ADB wait-for-device
# wait-for-device returns as soon as adbd is up; boot is not finished yet.
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
echo "Boot completed."
sleep 20  # let the module's service.sh start GHOST and re-apply battery exemptions

echo
echo "Installed now: $($ADB shell "dumpsys package $PKG | grep -E 'versionCode|versionName' | head -2" | tr -d '\r' | tr '\n' ' ')"
echo "Service:       $($ADB shell "dumpsys activity services $PKG | grep -E 'isForeground|types=' | head -1" | tr -d '\r')"
echo "PID:           $($ADB shell "pidof $PKG" | tr -d '\r')"
