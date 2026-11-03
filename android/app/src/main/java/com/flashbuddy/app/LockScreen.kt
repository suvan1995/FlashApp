package com.flashbuddy.app

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/**
 * Lock screen mode, for phones whose lock screen can't show a live wallpaper
 * (e.g. Samsung). A foreground service watches the flashlight and, while the
 * phone is locked, shows the scene over the lock screen.
 */
object LockMode {
    private const val PREFS = "flashbuddy"
    private const val KEY = "lock_mode"

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        val i = Intent(c, LockWatchService::class.java)
        if (on) c.startForegroundService(i) else c.stopService(i)
    }

    fun canShowOverLock(c: Context) = Settings.canDrawOverlays(c)
}

class LockWatchService : Service() {
    private var torch: TorchWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (torch == null) {
            torch = TorchWatcher(this) { on -> if (on) maybeShowScene() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        torch?.stop()
        torch = null
        super.onDestroy()
    }

    private fun maybeShowScene() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) return // unlocked: the live wallpaper handles it
        val open = Intent(this, LockSceneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (LockMode.canShowOverLock(this)) {
            try { startActivity(open); return } catch (_: Exception) {}
        }
        // Fallback: a full-screen notification, which Android shows over the lock screen.
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Flashlight Buddy")
            .setContentText("Your flashlight is on")
            .setCategory(Notification.CATEGORY_STATUS)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ALERT_ID, n) } catch (_: SecurityException) {}
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, "Lock screen mode", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Flashlight Buddy watches for the flashlight on the lock screen"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Lock screen animation", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Lock screen mode is on")
            .setContentText("Your buddy appears when the flashlight turns on")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(WATCH_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(WATCH_ID, n)
        }
    }

    companion object {
        private const val WATCH_CHANNEL = "watch"
        private const val ALERT_CHANNEL = "alert"
        private const val WATCH_ID = 1
        const val ALERT_ID = 2
    }
}

/** Full-screen scene shown on top of the lock screen while the torch is on. */
class LockSceneActivity : Activity() {
    private lateinit var torch: TorchWatcher
    private lateinit var pointing: PointingTracker
    private lateinit var view: SceneView
    private var sawTorchOn = false

    private val unlocked = object : BroadcastReceiver() {
        // Once the phone is unlocked, the home screen wallpaper takes over.
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(NotificationManager::class.java).cancel(LockWatchService.ALERT_ID)

        pointing = PointingTracker(this)
        torch = TorchWatcher(this) { on ->
            if (on) { sawTorchOn = true; pointing.recenter() } else if (sawTorchOn) finish()
        }
        view = SceneView(this, torch, pointing)
        view.skipDayScene = true
        // Tap to go to the normal lock screen (e.g. to unlock).
        view.setOnClickListener { finish() }
        setContentView(view)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onResume() {
        super.onResume()
        torch.start()
        pointing.start()
        view.running = true
        // If the torch is already off by the time we open, close right away.
        view.postDelayed({ if (!torch.isOn) finish() }, 600)
    }

    override fun onPause() {
        view.running = false
        pointing.stop()
        torch.stop()
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlocked) } catch (_: Exception) {}
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && LockMode.isEnabled(c)) {
            try { c.startForegroundService(Intent(c, LockWatchService::class.java)) } catch (_: Exception) {}
        }
    }
}
