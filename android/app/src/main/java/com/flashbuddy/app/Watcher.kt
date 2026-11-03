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
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/**
 * Main mode: the user keeps their own wallpaper. A small foreground service
 * watches the system flashlight and shows the animation only while it is on,
 * on the lock screen and the home screen alike.
 */
object Watch {
    private const val PREFS = "flashbuddy"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean("watch", false)
    fun seeThrough(c: Context) = prefs(c).getBoolean("see_through", false)
    fun setSeeThrough(c: Context, on: Boolean) = prefs(c).edit().putBoolean("see_through", on).apply()

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("watch", on).apply()
        val i = Intent(c, WatchService::class.java)
        if (on) c.startForegroundService(i) else c.stopService(i)
    }

    /** "Display over other apps" lets us open the animation from the background. */
    fun canDrawOverApps(c: Context) = Settings.canDrawOverlays(c)
}

class WatchService : Service() {
    private var torch: TorchWatcher? = null
    private var pointing: PointingTracker? = null
    private var overlay: SceneView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (torch == null) {
            pointing = PointingTracker(this)
            torch = TorchWatcher(this) { on -> if (on) show() else hideOverlay() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        torch?.stop()
        torch = null
        super.onDestroy()
    }

    private fun show() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = km.isKeyguardLocked
        if (!locked && Watch.seeThrough(this) && Watch.canDrawOverApps(this)) {
            showOverlay()
            return
        }
        val open = Intent(this, SceneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Watch.canDrawOverApps(this)) {
            try { startActivity(open); return } catch (_: Exception) {}
        }
        // Without "Display over other apps", Android only lets us show it through a
        // full-screen notification (works on the lock screen; a pop-up banner otherwise).
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Flashlight Buddy")
            .setContentText("Your flashlight is on. Tap to watch your buddy.")
            .setCategory(Notification.CATEGORY_STATUS)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ALERT_ID, n) } catch (_: SecurityException) {}
    }

    /** See-through mode: the scene floats over everything and touches pass through. */
    private fun showOverlay() {
        if (overlay != null) return
        val t = torch ?: return
        val p = pointing ?: return
        p.recenter(); p.start()
        val v = SceneView(this, t, p).apply { skipDayScene = true; running = true; alpha = 0f }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // Android only passes touches through an overlay that is at most 80% opaque.
        lp.alpha = 0.78f
        try {
            getSystemService(WindowManager::class.java).addView(v, lp)
            v.animate().alpha(1f).setDuration(500).start()
            overlay = v
        } catch (e: Exception) {
            p.stop()
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        v.running = false
        pointing?.stop()
        v.animate().alpha(0f).setDuration(500).withEndAction {
            try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
        }.start()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, "Flashlight watcher", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Flashlight Buddy waits for the flashlight"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Flashlight animation", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Flashlight Buddy is on")
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

/** Full-screen scene, shown over the lock screen or home screen while the torch is on. */
class SceneActivity : Activity() {
    private lateinit var torch: TorchWatcher
    private lateinit var pointing: PointingTracker
    private lateinit var view: SceneView
    private var sawTorchOn = false

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
        getSystemService(NotificationManager::class.java).cancel(WatchService.ALERT_ID)

        pointing = PointingTracker(this)
        torch = TorchWatcher(this) { on ->
            if (on) { sawTorchOn = true; pointing.recenter() } else if (sawTorchOn) finishNicely()
        }
        view = SceneView(this, torch, pointing)
        view.skipDayScene = true
        view.alpha = 0f
        // Tap to close it and get back to what was underneath.
        view.setOnClickListener { finishNicely() }
        setContentView(view)
        view.animate().alpha(1f).setDuration(450).start()
    }

    private fun finishNicely() {
        if (isFinishing) return
        view.animate().alpha(0f).setDuration(450).withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }

    override fun onResume() {
        super.onResume()
        torch.start()
        pointing.start()
        view.running = true
        // If the torch is already off by the time we open, close right away.
        view.postDelayed({ if (!torch.isOn) finishNicely() }, 800)
    }

    override fun onPause() {
        view.running = false
        pointing.stop()
        torch.stop()
        super.onPause()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ok = i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (ok && Watch.isEnabled(c)) {
            try { c.startForegroundService(Intent(c, WatchService::class.java)) } catch (_: Exception) {}
        }
    }
}
