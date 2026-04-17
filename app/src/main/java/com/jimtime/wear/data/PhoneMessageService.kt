package com.jimtime.wear.data

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class PhoneMessageService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith("/jimtime")) return

        val json = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
        val cmd = json.optString("cmd")

        when (cmd) {
            MessagePaths.CMD_START_SESSION -> {
                val type = json.optString("type", "run")
                val startedAt = json.optLong("startedAt", System.currentTimeMillis())
                SessionRepository.startSession(type, startedAt)
            }
            MessagePaths.CMD_STOP_SESSION  -> SessionRepository.stopSession()
            MessagePaths.CMD_PAUSE_SESSION -> SessionRepository.pauseSession()
            MessagePaths.CMD_RESUME_SESSION -> SessionRepository.resumeSession()
        }
    }
}
