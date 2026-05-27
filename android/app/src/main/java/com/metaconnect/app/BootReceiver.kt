package com.metaconnect.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-start MetaConnect voice service when phone boots up.
 * The service starts listening for "Hey Claude" immediately.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("MetaConnect", "Boot completed - starting voice service")

            val prefs = context.getSharedPreferences("metaconnect_prefs", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "") ?: ""

            if (serverUrl.isNotEmpty()) {
                val serviceIntent = Intent(context, VoiceService::class.java).apply {
                    putExtra("server_url", serverUrl)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
