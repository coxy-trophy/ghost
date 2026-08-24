#!/system/bin/sh
# GHOST boot-start + battery exemptions.
# Runs late in boot as root (Magisk late_start service). Two jobs:
#   1. Start the GHOST foreground service as root — this bypasses ColorOS's aggressive
#      auto-start block (ColorOS does NOT cold-start an app for BOOT_COMPLETED; only an
#      already-running process receives it). Root (uid 0) can start a non-exported service
#      and is exempt from the API-34 background-FGS-start restriction.
#   2. Re-apply the Doze/appops/standby exemptions so they survive every reboot.
#
# Kill switches:
#   - Soft (session): tap "Stop GHOST" in the app or the notification. Stops until next boot.
#   - Hard (persistent): disable this Magisk module in the Magisk app, then reboot. GHOST will
#     not start at boot and priv-app status is removed. (Uninstalling the app is the full reset.)
#
# This module is intentionally an "always-on" daemon: service.sh starts GHOST on every boot
# regardless of the in-app enabled flag (which the boot receiver — a secondary path — reads).
# To keep GHOST off, disable the module (hard kill).

MODDIR=${0%/*}
PKG=com.coxy.ghost
LOGTAG=GHOSTModule

# NOTE: call the binary by absolute path. A helper named `log` that calls `log` resolves to
# itself, not to /system/bin/log — the shell function shadows the command and recurses until the
# shell dies, so the first log call hangs and nothing after it (including the `am` start below)
# ever runs. That silently broke boot-start.
log() { /system/bin/log -t "$LOGTAG" -p i "$*"; }

# Wait for the system to finish booting.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# ...and then wait for the user to actually unlock.
#
# This block used to consist of the loop above alone, under a comment claiming it waited for the
# user to be unlocked. It did not. On a file-based-encryption device `sys.boot_completed` goes to 1
# well BEFORE the first unlock, while the app's credential-encrypted storage — /data/data/$PKG,
# holding the DataStore, the memory DB and the staged model — does not yet exist. Starting the
# service there fails, and because nothing retries, GHOST stayed dead until somebody happened to
# open the app by hand. Observed 2026-08-19: the phone rebooted at 00:03, was still locked, and
# GHOST was simply not running an hour later.
#
# ColorOS is why this has to be solved here rather than with a BOOT_COMPLETED receiver: it does not
# cold-start an app for broadcasts, which is the whole reason this script exists.
#
# The data directory appearing IS the unlock signal — it is exactly the resource the service needs.
i=0
until [ -d "/data/data/$PKG" ]; do
    i=$((i + 1))
    # ~every 10 minutes, so a phone left locked overnight leaves a trail rather than silence.
    [ $((i % 20)) -eq 0 ] && log "still locked after $((i / 2)) minutes; waiting to start GHOST"
    sleep 30
done

# Give the system a moment to settle package manager / AM after unlock.
sleep 8

log "boot completed; starting GHOST as root"

# --- 1. Start the GHOST foreground service (root bypasses ColorOS cold-start + exported=false) ---
# Try the explicit FGS start; fall back to plain startservice.
am start-foreground-service -n "$PKG/com.coxy.ghost.GhostService" 2>/dev/null \
    && log "start-foreground-service issued" \
    || { log "start-foreground-service failed, trying startservice"; am startservice -n "$PKG/com.coxy.ghost.GhostService" 2>/dev/null; }

# Give the service a moment to come up and post its foreground notification, then confirm.
sleep 6
if pidof "$PKG" >/dev/null 2>&1; then
    log "GHOST process is up (pid=$(pidof "$PKG"))"
else
    log "GHOST process not yet up — will retry in 20s"
    sleep 20
    am start-foreground-service -n "$PKG/com.coxy.ghost.GhostService" 2>/dev/null
    log "retry issued; pid=$(pidof "$PKG" 2>/dev/null || echo none)"
fi

# --- 2. Re-apply battery / Doze / appops / standby exemptions (survive every reboot) ---
log "re-applying battery exemptions"
dumpsys deviceidle whitelist +"$PKG" 2>/dev/null
cmd appops set "$PKG" RUN_IN_BACKGROUND allow 2>/dev/null
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
cmd appops set "$PKG" REQUEST_IGNORE_BATTERY_OPTIMIZATIONS allow 2>/dev/null
am set-standby-bucket "$PKG" active 2>/dev/null

log "boot-start sequence complete"