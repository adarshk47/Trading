package com.scalpai.stocksignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Auto-start overlay service on boot so it's always watching
            val serviceIntent = Intent(context, OverlayService::class.java)
            context.startService(serviceIntent)
        }
    }
}
