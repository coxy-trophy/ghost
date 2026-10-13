package com.coxy.ghost.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/** Framework gesture bridge; GhostService retains its existing single capture/session owner. */
class GhostVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)

    private class Session(context: Context) : VoiceInteractionSession(context) {
        override fun onCreate() {
            super.onCreate()
            setUiEnabled(false)
        }
        override fun onPrepareShow(args: Bundle?, showFlags: Int) {
            super.onPrepareShow(args, showFlags)
            setUiEnabled(false)
            Log.i("GhostPlatformVoice", "Preparing UI-less framework session")
        }
        override fun onHide() {
            super.onHide()
            Log.i("GhostPlatformVoice", "Framework session hidden")
            finish()
        }
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            try {
                context.startForegroundService(Intent("com.coxy.ghost.action.SUMMON")
                    .setClassName(context.packageName, "com.coxy.ghost.GhostService")
                    .putExtra("source", "assist"))
                Log.i("GhostPlatformVoice", "Framework summon forwarded")
            } catch (error: Exception) {
                Log.w("GhostPlatformVoice", "Framework summon failed", error)
            }
            // Let the framework acknowledge onShow and own hiding; do not tear down here.
        }
    }
}
