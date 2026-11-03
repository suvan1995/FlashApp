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
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Setup screen with a live preview of the wallpaper. */
class MainActivity : Activity() {
    private lateinit var preview: SceneView
    private lateinit var torch: TorchWatcher
    private lateinit var pointing: PointingTracker
    private lateinit var status: TextView
    private lateinit var torchBtn: Button
    private lateinit var setBtn: Button
    private lateinit var clearPhotoBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointing = PointingTracker(this)
        torch = TorchWatcher(this) { on ->
            if (on) pointing.recenter()
            refresh()
        }
        preview = SceneView(this, torch, pointing)

        val dp = resources.displayMetrics.density
        fun btn(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.parseColor("#2a1d00") else Color.parseColor("#10213a"))
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(if (primary) Color.parseColor("#ffc928") else Color.parseColor("#E6FFFFFF"))
            }
            setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { onClick() }
        }

        val title = TextView(this).apply {
            text = "Flashlight Buddy"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#10213a"))
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#10213a"))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor("#CCFFFFFF")) }
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        setBtn = btn("Set as wallpaper", true) { applyWallpaper() }
        torchBtn = btn("Flashlight on", false) {
            if (!torch.setTorch(!torch.isOn)) toast("This phone's flashlight couldn't be switched from here.")
        }
        val photoBtn = btn("Choose main background photo", false) { pickPhoto() }
        clearPhotoBtn = btn("Use the cartoon daytime scene", false) {
            Prefs.photoFile(this).delete()
            preview.reloadPhoto()
            refresh()
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (28 * dp).toInt())
            val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() } }
            addView(status, lp())
            addView(setBtn, lp())
            addView(torchBtn, lp())
            addView(photoBtn, lp())
            addView(clearPhotoBtn, lp())
        }

        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = (48 * dp).toInt() })
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        titleView = title
    }

    private var titleView: TextView? = null

    override fun onResume() {
        super.onResume()
        torch.start()
        pointing.start()
        preview.running = true
        refresh()
    }

    override fun onPause() {
        preview.running = false
        pointing.stop()
        torch.stop()
        super.onPause()
    }

    private fun isOurWallpaper(): Boolean =
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName

    private fun refresh() {
        val on = torch.isOn
        torchBtn.text = if (on) "Flashlight off" else "Flashlight on"
        setBtn.visibility = if (isOurWallpaper()) View.GONE else View.VISIBLE
        clearPhotoBtn.visibility = if (Prefs.photoFile(this).exists()) View.VISIBLE else View.GONE
        titleView?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(500)?.start()
        status.text = when {
            on && pointing.available -> "Point your phone left, right, up or down. Your buddy follows."
            on -> "This phone has no motion sensor, so the beam stays level."
            isOurWallpaper() -> "Wallpaper is set. Turn on the flashlight from quick settings and go to your home screen."
            else -> "Tap \"Set as wallpaper\", then turn on your flashlight anytime."
        }
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

    @Suppress("DEPRECATION")
    private fun pickPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(intent, PICK_PHOTO) } catch (e: Exception) { toast("No photo picker found.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK) return
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                Prefs.photoFile(this).outputStream().use { input.copyTo(it) }
            }
            preview.reloadPhoto()
            refresh()
            toast("Main background updated.")
        } catch (e: Exception) {
            toast("Couldn't read that photo. Try another one.")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { private const val PICK_PHOTO = 7 }
}

/** In-app live preview using the same Scene as the wallpaper. */
class SceneView(context: Context, private val torch: TorchWatcher, private val pointing: PointingTracker) : View(context) {
    private val scene = Scene()
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
        scene.update(dt, torch.isOn, pointing.yaw, pointing.pitch)
        scene.render(canvas, width.toFloat(), height.toFloat(), (now - start) / 1000f)
        if (running) postInvalidateOnAnimation()
    }
}
