package com.coxy.ghost.action

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * The list of apps that can actually be opened, as [AppMatcher.AppEntry] pairs.
 *
 * Only launchable apps are indexed — resolved through an ACTION_MAIN/CATEGORY_LAUNCHER query rather
 * than `getInstalledApplications`, because "apps GHOST can open" and "packages on the device" are
 * different sets, and the second one is mostly services with no label worth matching.
 *
 * The manifest declares the matching `<queries>` element. Without it this returns almost nothing on
 * targetSdk 30+ — package visibility filtering is silent, so the symptom is not an error but an
 * index that only contains GHOST herself.
 */
object AppIndex {

    private const val TAG = "AppIndex"

    /**
     * Short by design. A newly installed app should be openable by name within seconds of being
     * installed, and rebuilding costs one PackageManager query — this is only ever hit when an
     * action actually runs, not per frame or per turn.
     */
    private const val TTL_MS = 30_000L

    @Volatile private var cache: List<AppMatcher.AppEntry> = emptyList()
    @Volatile private var loadedAt = 0L

    /** The installed launchable apps, rebuilt if the cache has gone stale. */
    fun entries(context: Context): List<AppMatcher.AppEntry> {
        val now = SystemClock.elapsedRealtime()
        val cached = cache
        if (cached.isNotEmpty() && now - loadedAt < TTL_MS) return cached
        val fresh = load(context)
        cache = fresh
        loadedAt = now
        return fresh
    }

    /** Drop the cache, so the next [entries] call rebuilds. */
    fun invalidate() {
        cache = emptyList()
        loadedAt = 0L
    }

    /** Convenience: resolve a name or package against the live index. */
    fun resolve(context: Context, query: String): AppMatcher.Resolution =
        AppMatcher.resolve(query, entries(context))

    private fun load(context: Context): List<AppMatcher.AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // Flag 0, not MATCH_DEFAULT_ONLY. That flag keeps only activities declaring
        // CATEGORY_DEFAULT, and a launcher entry conventionally declares MAIN + LAUNCHER and
        // nothing else — GHOST's own manifest included. It silently dropped every app that follows
        // the convention, which the instrumented test caught by GHOST failing to find herself.
        val resolved = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrElse {
            Log.w(TAG, "could not enumerate launchable apps", it)
            return emptyList()
        }
        // Several launcher entries can share a package (an app with two icons); the package is what
        // gets launched, so collapse them and keep the first label.
        val entries = resolved.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: pkg
                AppMatcher.AppEntry(pkg, label)
            }
            .distinctBy { it.pkg }
            .toList()
        Log.i(TAG, "indexed ${entries.size} launchable apps")
        return entries
    }
}
