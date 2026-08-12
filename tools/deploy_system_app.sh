#!/bin/zsh
# Deploy a freshly built GHOST APK into the Magisk module that magic-mounts it as a priv-app.
#
# GHOST runs from /system/priv-app/GHOST (SYSTEM|PRIVILEGED), which is what grants the permanent
# FGS-start allowlist a normal app never gets. The root fs is erofs and read-only, so "install"
# means: replace the APK inside the Magisk module, then reboot so the module remounts it and
# PackageManager rescans.
#
# The native libs under lib/arm64/ are only rebuilt when :lib changes. Kotlin-only changes reuse
# them, so this script leaves them alone by default (--libs re-extracts them).
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
#   ./deploy_system_app.sh --libs         # also re-extract arm64 native libs from the APK
#   ./deploy_system_app.sh --no-reboot    # stage only; PM will not see the change until a reboot
#   ./deploy_system_app.sh --no-backup    # skip the app-data backup (not recommended)

set -e

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG=com.coxy.ghost
SCRIPT_DIR="${0:A:h}"
APK="$SCRIPT_DIR/../app/build/outputs/apk/debug/app-debug.apk"
MODULE=/data/adb/modules/ghost_systemapp/system/priv-app/GHOST
STAGE=/sdcard/Download/GHOST-new.apk

BACKUP_DIR="${GHOST_BACKUP_DIR:-$HOME/ghost-backups/appdata}"

DO_LIBS=0
DO_REBOOT=1
DO_BACKUP=1
for arg in "$@"; do
  case "$arg" in
    --libs) DO_LIBS=1 ;;
    --no-reboot) DO_REBOOT=0 ;;
    --no-backup) DO_BACKUP=0 ;;
    *) echo "unknown flag: $arg"; exit 1 ;;
  esac
done

[ -f "$APK" ] || { echo "No APK at $APK — run ./gradlew :app:assembleDebug first"; exit 1; }

echo "Local APK:  $APK ($(wc -c < "$APK" | tr -d ' ') bytes)"
LOCAL_MD5=$(md5 -q "$APK")
echo "Local md5:  $LOCAL_MD5"
echo "Installed:  $($ADB shell "dumpsys package $PKG | grep -E 'versionCode|versionName' | head -2" | tr -d '\r' | tr '\n' ' ')"
echo

# --- 0. Back up app data BEFORE touching the module (see the header note) ---
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
    $ADB push "$so" "/sdcard/Download/$(basename "$so")" >/dev/null
    $ADB shell "su -c 'mv /sdcard/Download/$(basename "$so") $MODULE/lib/arm64/ && \
      chown root:root $MODULE/lib/arm64/$(basename "$so") && \
      chmod 644 $MODULE/lib/arm64/$(basename "$so") && \
      chcon u:object_r:system_file:s0 $MODULE/lib/arm64/$(basename "$so")'"
  done
  rm -rf "$TMP"
  echo "Native libs replaced."
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
