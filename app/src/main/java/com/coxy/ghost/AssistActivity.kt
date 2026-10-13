package com.coxy.ghost

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.coxy.ghost.perception.SummonSource

/**
 * The assist-gesture entry point — "like Siri", summoned by the system's own assist gesture.
 *
 * GHOST is registered as the device assistant (`settings put secure assistant
 * com.coxy.ghost/.AssistActivity`, applied by the Magisk module). The OS then delivers
 * `android.intent.action.ASSIST` here on the assist gesture — corner-swipe or power-hold, whichever
 * ColorOS is set to. This is a trampoline with no UI: it hands the summon to [GhostService], which
 * owns the overlay, and finishes immediately, so what the user sees is the orb, not an app launch.
 *
 * A full `VoiceInteractionService` was the alternative. It additionally requires a
 * `RecognitionService` component and framework hotword plumbing GHOST does not use (recognition runs
 * through AiAi/SODA in [com.coxy.ghost.perception.MicListener]), and it is the more brittle path on
 * this OEM. The assistant-role activity is the standard route for a non-voice assist app and reaches
 * the same place — including over the lock screen, since the assist gesture is allowed there.
 *
 * **Runs in its own `:assist` process** (declared in the manifest). The main GHOST process holds ~1 GB
 * of native model memory, and creating even a windowless activity there forces a `DecorView` build
 * (the launch-transition machinery calls `getDecorView`) whose `registerNativeAllocation` triggers a
 * GC against that huge heap — long enough to blow the foreground-service ANR while the queued
 * `ACTION_SUMMON` waits behind it. That ANR was observed the first time this was wired. A separate,
 * model-free process launches the trampoline instantly; the service intent still lands in the main
 * process, which shows the overlay exactly as it does for every other trigger.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "assist gesture → summon")
        // Explicit component + package so the intent resolves from the :assist process into the main
        // process's service. startForegroundService is safe here: the assist gesture is a foreground
        // launch, so this process may promote a foreground service.
        val intent = Intent(GhostService.ACTION_SUMMON).apply {
            setClassName(packageName, "com.coxy.ghost.GhostService")
            putExtra(GhostService.EXTRA_SOURCE, SummonSource.ASSIST.label)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure { Log.w(TAG, "failed to start summon from assist gesture", it) }

        finish()
    }

    companion object {
        private const val TAG = "AssistActivity"
    }
}
