package com.flashbuddy.app

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Setup screen, with a live preview of the animation behind it. */
class MainActivity : Activity() {
    private lateinit var preview: SceneView
    private lateinit var torch: TorchWatcher
    private lateinit var pointing: PointingTracker
    private lateinit var status: TextView
    private lateinit var mainBtn: Button
    private lateinit var permBtn: Button
    private lateinit var seeBtn: Button
    private lateinit var torchBtn: Button
    private var titleView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointing = PointingTracker(this)
        torch = TorchWatcher(this) { on ->
            if (on) pointing.recenter()
            refresh()
        }
        preview = SceneView(this, torch, pointing)

        val dp = resources.displayMetrics.density
        fun btn(primary: Boolean, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = if (small) 14f else 16f
            typeface = if (small) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.parseColor("#2a1d00") else Color.parseColor("#10213a"))
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(if (primary) Color.parseColor("#ffc928") else Color.parseColor("#E6FFFFFF"))
            }
            val ph = if (small) 16 else 22; val pv = if (small) 8 else 12
            setPadding((ph * dp).toInt(), (pv * dp).toInt(), (ph * dp).toInt(), (pv * dp).toInt())
            setOnClickListener { onClick() }
        }

        val title = TextView(this).apply {
            text = "Flashlight Buddy"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#10213a"))
            gravity = Gravity.CENTER
        }
        titleView = title
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#10213a"))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor("#E6FFFFFF")) }
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        mainBtn = btn(true) { toggleWatch() }
        permBtn = btn(true) { askOverlayPermission() }
        seeBtn = btn(false, small = true) {
            Watch.setSeeThrough(this, !Watch.seeThrough(this))
            refresh()
        }
        torchBtn = btn(false, small = true) {
            if (!torch.setTorch(!torch.isOn)) toast("This phone's flashlight couldn't be switched from here.")
        }
        val wallBtn = btn(false, small = true) { applyWallpaper() }.apply { text = "Use as my wallpaper instead (replaces mine)" }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (28 * dp).toInt())
            val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() } }
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(permBtn, lp())
            addView(mainBtn, lp())
            addView(seeBtn, lp())
            addView(torchBtn, lp())
            addView(wallBtn, lp())
        }

        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = (48 * dp).toInt() })
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        torch.start()
        pointing.start()
        preview.running = true
        if (Watch.isEnabled(this)) Watch.setEnabled(this, true)
        refresh()
    }

    override fun onPause() {
        preview.running = false
        pointing.stop()
        torch.stop()
        super.onPause()
    }

    private fun refresh() {
        val on = torch.isOn
        val watching = Watch.isEnabled(this)
        val perm = Watch.canDrawOverApps(this)
        mainBtn.text = if (watching) "Turn Flashlight Buddy off" else "Turn Flashlight Buddy on"
        permBtn.text = "Allow \"Display over other apps\""
        permBtn.visibility = if (perm) View.GONE else View.VISIBLE
        seeBtn.text = if (Watch.seeThrough(this)) "See-through: on" else "See-through: off"
        torchBtn.text = if (on) "Test: flashlight off" else "Test: flashlight on"
        titleView?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(500)?.start()
        status.text = when {
            on -> if (pointing.available) "Point your phone left, right, up or down. Your buddy follows."
                  else "This phone has no motion sensor, so the beam stays level."
            !perm -> "Step 1: allow \"Display over other apps\" so your buddy can appear on your home and lock screen."
            !watching -> "Step 2: turn Flashlight Buddy on. Your own wallpaper stays as it is."
            Watch.seeThrough(this) -> "Ready. When the flashlight turns on, your buddy appears on your lock screen, and floats see-through over your home screen so you can keep using your phone."
            else -> "Ready. When the flashlight turns on, your buddy fills the screen. Tap it or turn the flashlight off to go back."
        }
    }

    private fun toggleWatch() {
        val on = !Watch.isEnabled(this)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        Watch.setEnabled(this, on)
        if (on && !Watch.canDrawOverApps(this)) askOverlayPermission()
        refresh()
    }

    private fun askOverlayPermission() {
        toast("Find Flashlight Buddy and switch on \"Allow display over other apps\", then come back.")
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun applyWallpaper() {
        val component = ComponentName(this, FlashWallpaperService::class.java)
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                toast("Pick \"Flashlight Buddy\" from the list.")
            } catch (e2: Exception) {
                toast("Open your phone's wallpaper settings and pick Flashlight Buddy.")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** In-app live preview using the same Scene as the wallpaper. */
class SceneView(context: Context, private val torch: TorchWatcher, private val pointing: PointingTracker) : View(context) {
    private val scene = Scene()
    var skipDayScene = false
    private var last = 0L
    private val start = SystemClock.elapsedRealtime()
    var running = false
        set(v) { field = v; last = 0L; if (v) postInvalidateOnAnimation() }

    fun reloadPhoto() {
        scene.dayPhoto = Prefs.loadPhoto(context, maxOf(width, height, 1080))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reloadPhoto()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (last == 0L) 0.016f else (now - last) / 1000f
        last = now
        scene.update(dt, torch.isOn || skipDayScene, pointing.yaw, pointing.pitch)
        if (skipDayScene && scene.night < 1f) scene.night = 1f
        scene.render(canvas, width.toFloat(), height.toFloat(), (now - start) / 1000f)
        if (running) postInvalidateOnAnimation()
    }
}
