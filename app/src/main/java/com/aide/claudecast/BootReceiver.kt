package com.aide.claudecast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** On a TV device, opens ClaudeCast when the device finishes booting. Does nothing on phones. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TvMode.isTv(context)) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
