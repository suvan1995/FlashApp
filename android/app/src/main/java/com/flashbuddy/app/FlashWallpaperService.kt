package com.flashbuddy.app

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Live wallpaper: shows the main background while the flashlight is off and
 * the animated explorer while it is on. Sensors only run while the torch is on
 * and the wallpaper is visible, so it costs nothing the rest of the time.
 */
class FlashWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = FlashEngine()

    inner class FlashEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val scene = Scene()
        private val pointing = PointingTracker(this@FlashWallpaperService)
        private lateinit var torch: TorchWatcher
        private var visible = false
        private var width = 0f
        private var height = 0f
        private var lastFrame = 0L
        private var photoVersion = -1L
        private var sensorsOn = false
        private val start = SystemClock.elapsedRealtime()

        private val frame = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            torch = TorchWatcher(this@FlashWallpaperService) { on ->
                if (on) pointing.recenter()
                updateSensors()
                schedule(0)
            }
            torch.start()
            // Start in the right state if the torch is already on.
            scene.night = 0f
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            torch.stop()
            pointing.stop()
            super.onDestroy()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            updateSensors()
            if (v) {
                reloadPhotoIfChanged()
                lastFrame = 0L
                schedule(0)
            } else {
                handler.removeCallbacks(frame)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w.toFloat(); height = h.toFloat()
            photoVersion = -1L
            reloadPhotoIfChanged()
            schedule(0)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        private fun updateSensors() {
            val want = visible && torch.isOn
            if (want && !sensorsOn) pointing.start() else if (!want && sensorsOn) pointing.stop()
            sensorsOn = want
        }

        private fun reloadPhotoIfChanged() {
            val v = Prefs.photoVersion(this@FlashWallpaperService)
            if (v == photoVersion || width <= 0f) return
            photoVersion = v
            scene.dayPhoto = Prefs.loadPhoto(this@FlashWallpaperService, maxOf(width, height).toInt())
        }

        private fun schedule(delay: Long) {
            handler.removeCallbacks(frame)
            if (visible) handler.postDelayed(frame, delay)
        }

        private fun drawFrame() {
            if (!visible || width <= 0f) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1000f
            lastFrame = now
            val animating = scene.update(dt, torch.isOn, pointing.yaw, pointing.pitch)

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas()
                if (canvas != null) scene.render(canvas, width, height, (now - start) / 1000f)
            } catch (e: Exception) {
                // Surface went away mid-frame; the next visibility change redraws.
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }

            // ~60 fps while the flashlight scene runs; a slow tick for the
            // drifting clouds of the daytime scene; nothing for a still photo.
            when {
                animating -> schedule(16)
                scene.dayPhoto == null -> schedule(100)
            }
        }
    }
}
