package com.bastion.terminal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.launch
import com.bastion.terminal.link.LinkClient
import com.bastion.terminal.nav.LocationSource

/**
 * Keeps BASTION alive in the background (owner decision 2026-11-22: always
 * on — this is a dedicated device). Android stops background apps; a
 * foreground service with a permanent notification is the supported way to
 * stay running. While it runs, BASTION keeps its link to the Meshtastic app's
 * TAK server, keeps its GPS fix current, and can auto-answer with the screen
 * off. It does no work of its own beyond keeping those alive.
 */
class BastionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "BASTION running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while BASTION stays on to receive and answer mesh messages."
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("BASTION is on")
            .setContentText("Receiving mesh messages. Turn off in BASTION → Mesh.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
                try {
                    startForeground(ID, n, types)
                } catch (e: SecurityException) {
                    // Restarted in the background: Android refuses the location type there.
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                }
            } else {
                startForeground(ID, n)
            }
        } catch (t: Throwable) {
            Log.w("Bastion", "could not stay in the foreground: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        com.bastion.terminal.mesh.MeshInbox.reconnect(this)
        LinkClient.get(this).start()
        LocationSource.get(this).start()
        // After a restart of the phone this service is all that runs: nobody has opened BASTION, so nothing has
        // opened the reference library or the maps. Without this a radio question got "no answer in my offline
        // library" until someone opened the app (found on emulator 2026-11-23).
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.bastion.terminal.kiwix.ZimLibrary.get(app).restore() }
            runCatching { com.bastion.terminal.map.MapPacks.get(app).rescan() }
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "bastion_running"
        private const val ID = 7
        private const val PREFS = "service"

        /** On by default: a dedicated device's job is to stay on. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("always_on", true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("always_on", on).apply()
            sync(context)
        }

        /** Starts or stops the service to match the setting. Call from the foreground. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, BastionService::class.java)
            if (isEnabled(app)) {
                runCatching { app.startForegroundService(intent) }
                    .onFailure { Log.w("Bastion", "start refused: ${it.message}") }
            } else {
                app.stopService(intent)
            }
        }
    }
}
