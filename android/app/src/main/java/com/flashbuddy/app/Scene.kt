package com.flashbuddy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Procedural scene, ported from web/scene.js. Keep the two in sync.
 * Angles follow the canvas convention: 0 = right, positive = clockwise (down).
 */
class Scene {
    var scroll = 0f
    var walkPhase = 0f
    var facing = 1f
    var turn = 1f
    var beamAngle = 0f
    var night = 0f

    /** Optional photo used as the "main background" when the torch is off. */
    var dayPhoto: Bitmap? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    private class Palette(
        val skyTop: Int, val skyBot: Int, val hill: Int, val hill2: Int,
        val trunk: Int, val leaf: Int, val leaf2: Int,
        val ground: Int, val groundTop: Int, val dirt: Int,
    )

    private val day = Palette(
        c("#7ec8f0"), c("#d9f3ff"), c("#6fbf73"), c("#4fa25a"),
        c("#7a5232"), c("#3f9a4a"), c("#56b35d"),
        c("#5aa845"), c("#6cc055"), c("#8a6a43"),
    )
    private val nightP = Palette(
        c("#04061a"), c("#141d45"), c("#1f4a32"), c("#173c29"),
        c("#5a3a22"), c("#2c7a3a"), c("#3a8f45"),
        c("#2f6b2c"), c("#3d8436"), c("#5e4a33"),
    )
    private val flowers = intArrayOf(c("#ff6b8b"), c("#ffd93d"), c("#a78bfa"), c("#ffffff"), c("#ff9f43"))

    /** Returns true while something is still moving (so the caller keeps drawing). */
    fun update(dtIn: Float, torchOn: Boolean, yaw: Float, pitch: Float): Boolean {
        val dt = min(dtIn, 0.05f)
        val target = if (torchOn) 1f else 0f
        night = (night + sign(target - night) * dt * 1.6f).coerceIn(0f, 1f)

        val dead = (12 * PI / 180).toFloat()
        if (yaw > dead) facing = 1f else if (yaw < -dead) facing = -1f
        turn += (facing - turn) * min(1f, dt * 10f)

        val want = (-pitch).coerceIn((-70 * PI / 180).toFloat(), (65 * PI / 180).toFloat())
        beamAngle += (want - beamAngle) * min(1f, dt * 8f)

        if (night > 0.5f) {
            val look = 1f - min(1f, abs(beamAngle) / 1.2f) * 0.6f
            val speed = 55f * look * abs(turn)
            scroll += facing * speed * dt
            walkPhase += dt * 7f * look
        } else {
            val rest = (walkPhase / PI.toFloat()).roundToInt() * PI.toFloat()
            walkPhase += (rest - walkPhase) * min(1f, dt * 5f)
        }
        return night > 0f || torchOn || abs(turn - facing) > 0.01f
    }

    fun render(cv: Canvas, w: Float, h: Float, t: Float) {
        val u = min(h / 100f, w / 50f)
        val gy = h * 0.74f
        if (night < 1f) {
            val photo = dayPhoto
            if (photo != null) drawPhoto(cv, photo, w, h) else drawWorld(cv, w, h, u, gy, t, day, false, 255)
        }
        if (night > 0f) drawWorld(cv, w, h, u, gy, t, nightP, true, (night * 255).toInt())
    }

    private fun drawPhoto(cv: Canvas, b: Bitmap, w: Float, h: Float) {
        val s = max(w / b.width, h / b.height)
        val dw = b.width * s; val dh = b.height * s
        oval.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        p.reset(); p.isFilterBitmap = true
        cv.drawBitmap(b, null as Rect?, oval, p)
    }

    private fun drawWorld(cv: Canvas, w: Float, h: Float, u: Float, gy: Float, t: Float, P: Palette, isNight: Boolean, alpha: Int) {
        val layer = if (alpha < 255) cv.saveLayerAlpha(0f, 0f, w, h, alpha) else -1
        val sx = scroll * u * 0.08f

        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, 0f, 0f, gy, P.skyTop, P.skyBot, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null
        if (!isNight) {
            fill(c("#fff3b0")); cv.drawCircle(w * 0.8f, h * 0.16f, u * 6, p)
            drawClouds(cv, w, u, sx, t)
        }

        drawHills(cv, w, gy, u, sx * 0.15f, P.hill2, 14f, 0.7f)
        drawHills(cv, w, gy, u, sx * 0.3f, P.hill, 8f, 1.3f)

        val tw = u * 26; val tsx = sx * 0.55f
        val owls = ArrayList<FloatArray>()
        for (i in floor((tsx - tw) / tw).toInt()..ceil((tsx + w + tw) / tw).toInt()) {
            if (rnd(i, 1) < 0.3f) continue
            val x = i * tw + rnd(i, 2) * tw * 0.6f - tsx
            val th = u * (22 + rnd(i, 3) * 16)
            drawTree(cv, x, gy - u * 1.5f, th, u, P, rnd(i, 4))
            if (isNight && rnd(i, 5) > 0.82f) owls.add(floatArrayOf(x, gy - u * 1.5f - th * 0.55f, i.toFloat()))
        }

        fill(P.ground); cv.drawRect(0f, gy, w, h, p)
        fill(P.groundTop); cv.drawRect(0f, gy, w, gy + u * 2, p)
        fill(P.dirt); cv.drawRect(0f, gy + u * 6, w, gy + u * 11, p)

        val gw = u * 9
        for (i in floor((sx - gw) / gw).toInt()..ceil((sx + w + gw) / gw).toInt()) {
            val x = i * gw + rnd(i, 7) * gw - sx
            val r = rnd(i, 8)
            when {
                r < 0.45f -> drawGrass(cv, x, gy + u * 1.5f, u, P)
                r < 0.7f -> drawFlower(cv, x, gy + u * 1.5f, u, flowers[(rnd(i, 9) * flowers.size).toInt().coerceAtMost(flowers.size - 1)], t, i)
                r < 0.82f -> drawMushroom(cv, x, gy + u * 2, u)
                r < 0.92f -> drawRock(cv, x, gy + u * 2.5f, u)
            }
        }

        val cx = w * 0.5f; val footY = gy + u * 3.5f
        val hand = hand(cx, footY, u)

        if (isNight) {
            drawDarkness(cv, w, h, u, hand, cx, footY)
            drawStars(cv, w, gy, u, t, sx)
            drawMoon(cv, w, h, u)
            drawBeamHaze(cv, w, h, hand)
            for (o in owls) drawOwlEyes(cv, o[0], o[1], u, t, o[2].toInt())
            drawFireflies(cv, w, gy, u, t, sx)
        }
        drawCharacter(cv, cx, footY, u, isNight)
        if (layer >= 0) cv.restoreToCount(layer)
    }

    // ---- scenery ----------------------------------------------------------
    private fun drawClouds(cv: Canvas, w: Float, u: Float, sx: Float, t: Float) {
        fill(Color.argb(230, 255, 255, 255))
        for (i in 0 until 4) {
            val span = w + u * 40
            var x = (i * w * 0.37f - sx * 0.05f + t * u * 0.6f) % span
            if (x < 0) x += span
            x -= u * 20
            val y = u * (10 + (i * 7) % 15)
            cv.drawCircle(x, y, u * 4, p)
            cv.drawCircle(x + u * 4.5f, y - u * 2, u * 5, p)
            cv.drawCircle(x + u * 9, y, u * 4, p)
        }
    }

    private fun drawHills(cv: Canvas, w: Float, gy: Float, u: Float, off: Float, color: Int, height: Float, freq: Float) {
        fill(color)
        path.reset(); path.moveTo(0f, gy)
        var x = 0f
        while (x <= w + 8) {
            val k = (x + off) / (u * 30) * freq
            val y = gy - u * height - sin(k) * u * height * 0.5f - sin(k * 2.3f + 1) * u * height * 0.25f
            path.lineTo(x, y)
            x += 8f
        }
        path.lineTo(w, gy); path.close()
        cv.drawPath(path, p)
    }

    private fun drawTree(cv: Canvas, x: Float, base: Float, th: Float, u: Float, P: Palette, r: Float) {
        fill(P.trunk); cv.drawRect(x - u * 1.2f, base - th * 0.35f, x + u * 1.2f, base, p)
        for (k in 0 until 3) {
            val top = base - th + k * th * 0.2f
            val bw = u * (9 - k * 1.5f) * (0.85f + r * 0.3f)
            fill(if (k % 2 == 1) P.leaf2 else P.leaf)
            path.reset()
            path.moveTo(x, top); path.lineTo(x + bw, top + th * 0.42f); path.lineTo(x - bw, top + th * 0.42f); path.close()
            cv.drawPath(path, p)
        }
    }

    private fun drawGrass(cv: Canvas, x: Float, y: Float, u: Float, P: Palette) {
        stroke(P.groundTop, u * 0.5f)
        for (k in -1..1) cv.drawLine(x + k * u * 0.6f, y, x + k * u * 1.2f, y - u * 2.2f, p)
    }

    private fun drawFlower(cv: Canvas, x: Float, y: Float, u: Float, color: Int, t: Float, i: Int) {
        val sway = sin(t * 2 + i) * u * 0.3f
        stroke(c("#2e7d32"), u * 0.4f); cv.drawLine(x, y, x + sway, y - u * 3, p)
        fill(color)
        for (k in 0 until 5) {
            val a = k / 5f * 2 * PI.toFloat()
            cv.drawCircle(x + sway + cos(a) * u * 0.8f, y - u * 3 + sin(a) * u * 0.8f, u * 0.6f, p)
        }
        fill(c("#ffd54f")); cv.drawCircle(x + sway, y - u * 3, u * 0.5f, p)
    }

    private fun drawMushroom(cv: Canvas, x: Float, y: Float, u: Float) {
        fill(c("#f5ead6")); cv.drawRect(x - u * 0.6f, y - u * 2, x + u * 0.6f, y, p)
        fill(c("#e53935"))
        oval.set(x - u * 1.9f, y - u * 3.9f, x + u * 1.9f, y - u * 0.1f)
        cv.drawArc(oval, 180f, 180f, true, p)
        fill(Color.WHITE)
        cv.drawCircle(x - u * 0.8f, y - u * 2.8f, u * 0.35f, p)
        cv.drawCircle(x + u * 0.6f, y - u * 3.2f, u * 0.3f, p)
    }

    private fun drawRock(cv: Canvas, x: Float, y: Float, u: Float) {
        fill(c("#8d939c"))
        oval.set(x - u * 2.2f, y - u * 2.2f, x + u * 2.2f, y + u * 0.6f)
        cv.drawArc(oval, 180f, 180f, true, p)
    }

    // ---- character --------------------------------------------------------
    private class Hand(val x: Float, val y: Float, val dir: Float)

    private fun bob(u: Float) = abs(sin(walkPhase)) * u * 0.7f

    private fun hand(cx: Float, footY: Float, u: Float): Hand {
        val shX = u * 0.8f; val shY = -u * 14.5f - bob(u)
        val len = u * 9.5f
        val lx = shX + cos(beamAngle) * len; val ly = shY + sin(beamAngle) * len
        val s = if (turn == 0f) 1f else sign(turn)
        return Hand(cx + lx * turn, footY + ly, atan2(sin(beamAngle), cos(beamAngle) * s))
    }

    private fun drawCharacter(cv: Canvas, cx: Float, footY: Float, u: Float, dim: Boolean) {
        val swing = sin(walkPhase) * 0.55f
        val bob = bob(u)
        val coat = c(if (dim) "#d9a521" else "#ffc928")
        val coatDark = c(if (dim) "#a87b12" else "#e0a800")
        val pants = c(if (dim) "#27325a" else "#34447a")
        val boot = c(if (dim) "#3a2416" else "#5a3a22")
        val skin = c(if (dim) "#d9a888" else "#f6c9a5")
        val hat = c(if (dim) "#c23b3b" else "#e84a4a")

        cv.save()
        cv.translate(cx, footY)
        cv.scale(if (abs(turn) < 0.02f) 0.02f * sign(turn + 1e-4f) else turn, 1f)

        fill(Color.argb(64, 0, 0, 0)); oval.set(-u * 5, -u, u * 5, u); cv.drawOval(oval, p)
        cv.translate(0f, -bob)
        val hipY = -u * 7.5f
        fun leg(ang: Float, color: Int) {
            val fx = sin(ang) * u * 6.5f; val fy = hipY + cos(ang) * u * 6.5f
            stroke(color, u * 2.2f); cv.drawLine(0f, hipY, fx, fy, p)
            fill(boot); oval.set(fx + u * 0.8f - u * 1.8f, fy + u * 0.2f - u, fx + u * 0.8f + u * 1.8f, fy + u * 0.2f + u); cv.drawOval(oval, p)
        }
        leg(-swing, c("#1f2849"))
        stroke(coatDark, u * 2f)
        cv.drawLine(-u * 0.5f, -u * 14.5f, -u * 0.5f - sin(swing) * u * 4, -u * 9.5f, p)
        leg(swing, pants)

        fill(coat)
        path.reset()
        path.moveTo(-u * 3.2f, -u * 16)
        path.quadTo(-u * 4.8f, -u * 10, -u * 4.6f, -u * 6.8f)
        path.lineTo(u * 4.6f, -u * 6.8f)
        path.quadTo(u * 4.8f, -u * 10, u * 3.2f, -u * 16)
        path.close(); cv.drawPath(path, p)
        fill(coatDark)
        cv.drawCircle(u * 1.2f, -u * 12, u * 0.45f, p); cv.drawCircle(u * 1.2f, -u * 9.5f, u * 0.45f, p)

        val hx = u * 0.6f; val hy = -u * 20.5f
        fill(skin); cv.drawCircle(hx, hy, u * 4.6f, p)
        val ex = cos(beamAngle) * u * 0.6f; val ey = sin(beamAngle) * u * 0.6f
        for (ox in floatArrayOf(u * 1.2f, u * 3.4f)) {
            val ecx = hx + ox - u * 0.6f; val ecy = hy - u * 0.4f
            fill(Color.WHITE); oval.set(ecx - u * 1.05f, ecy - u * 1.3f, ecx + u * 1.05f, ecy + u * 1.3f); cv.drawOval(oval, p)
            fill(c("#1b1b2a")); cv.drawCircle(ecx + ex, ecy + ey, u * 0.55f, p)
        }
        fill(Color.argb(115, 255, 120, 120)); cv.drawCircle(hx + u * 3.4f, hy + u * 1.8f, u * 0.8f, p)
        stroke(c("#7a3b2e"), u * 0.4f)
        oval.set(hx + u * 2 - u, hy + u * 1.4f - u, hx + u * 2 + u, hy + u * 1.4f + u)
        cv.drawArc(oval, 11.5f, 134f, false, p)

        fill(hat)
        oval.set(hx - u * 4.8f, hy - u * 0.8f - u * 4.8f, hx + u * 4.8f, hy - u * 0.8f + u * 4.8f)
        cv.drawArc(oval, 183.6f, 172.8f, false, p)
        cv.drawRect(hx - u * 5, hy - u * 1.6f, hx + u * 5, hy, p)
        fill(Color.WHITE); cv.drawCircle(hx - u * 1.5f, hy - u * 5.8f, u * 1.3f, p)

        val shX = u * 0.8f; val shY = -u * 14.5f
        val handX = shX + cos(beamAngle) * u * 5.5f; val handY = shY + sin(beamAngle) * u * 5.5f
        stroke(coat, u * 2.1f); cv.drawLine(shX, shY, handX, handY, p)
        cv.save()
        cv.translate(handX, handY); cv.rotate(Math.toDegrees(beamAngle.toDouble()).toFloat())
        fill(c("#4a5568")); cv.drawRect(-u * 0.5f, -u * 0.7f, u * 2.7f, u * 0.7f, p)
        fill(c("#718096")); cv.drawRect(u * 2.5f, -u * 1.1f, u * 4f, u * 1.1f, p)
        fill(c(if (dim && night > 0.5f) "#fffbe0" else "#cbd5e0")); cv.drawRect(u * 3.8f, -u * 0.95f, u * 4.2f, u * 0.95f, p)
        cv.restore()
        fill(skin); cv.drawCircle(handX, handY, u * 1.1f, p)
        cv.restore()
    }

    // ---- night effects ----------------------------------------------------
    private fun beamPath(hand: Hand, len: Float, spread: Float): Path {
        val bp = Path()
        bp.moveTo(hand.x, hand.y)
        oval.set(hand.x - len, hand.y - len, hand.x + len, hand.y + len)
        bp.arcTo(oval, Math.toDegrees((hand.dir - spread).toDouble()).toFloat(), Math.toDegrees((2 * spread).toDouble()).toFloat(), false)
        bp.close()
        return bp
    }

    private fun drawDarkness(cv: Canvas, w: Float, h: Float, u: Float, hand: Hand, cx: Float, footY: Float) {
        val layer = cv.saveLayer(0f, 0f, w, h, null)
        cv.drawColor(Color.argb(240, 2, 3, 12))
        val len = hypot(w, h) * 0.85f
        val flicker = 0.97f + Math.random().toFloat() * 0.03f
        p.reset(); p.isAntiAlias = true; p.xfermode = dstOut
        p.shader = RadialGradient(hand.x, hand.y, len,
            intArrayOf(Color.argb((flicker * 255).toInt(), 0, 0, 0), Color.argb(217, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(beamPath(hand, len, 0.36f), p)
        p.alpha = 128
        cv.drawPath(beamPath(hand, len, 0.46f), p)
        p.alpha = 255
        val sy = footY - u * 11
        p.shader = RadialGradient(cx, sy, u * 18, Color.argb(140, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, sy, u * 18, p)
        p.shader = null; p.xfermode = null
        cv.restoreToCount(layer)
    }

    private fun drawBeamHaze(cv: Canvas, w: Float, h: Float, hand: Hand) {
        val len = hypot(w, h) * 0.85f
        p.reset(); p.isAntiAlias = true; p.xfermode = screen
        p.shader = RadialGradient(hand.x, hand.y, len,
            intArrayOf(Color.argb(71, 255, 240, 190), Color.argb(15, 255, 230, 160), Color.argb(0, 255, 230, 160)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(beamPath(hand, len, 0.34f), p)
        p.shader = null; p.xfermode = null
    }

    private fun drawStars(cv: Canvas, w: Float, gy: Float, u: Float, t: Float, sx: Float) {
        for (i in 0 until 70) {
            var x = (rnd(i, 11) * w * 1.5f - sx * 0.02f) % w; if (x < 0) x += w
            val y = rnd(i, 12) * gy * 0.75f
            val a = 0.4f + 0.6f * abs(sin(t * (0.6f + rnd(i, 13) * 2) + i))
            fill(Color.argb((a * 0.9f * 255).toInt(), 255, 255, 240))
            cv.drawCircle(x, y, u * (0.15f + rnd(i, 14) * 0.25f), p)
        }
    }

    private fun drawMoon(cv: Canvas, w: Float, h: Float, u: Float) {
        val x = w * 0.82f; val y = h * 0.13f; val r = u * 4.5f
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(x, y, r * 3, Color.argb(64, 220, 230, 255), Color.argb(0, 220, 230, 255), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r * 3, p); p.shader = null
        val moon = Path()
        moon.addCircle(x, y, r, Path.Direction.CW)
        moon.addCircle(x + r * 0.45f, y - r * 0.2f, r * 0.9f, Path.Direction.CW)
        moon.fillType = Path.FillType.EVEN_ODD
        val clip = Path(); clip.addCircle(x, y, r, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        fill(c("#eef2ff")); cv.drawPath(moon, p)
        cv.restore()
    }

    private fun drawOwlEyes(cv: Canvas, x: Float, y: Float, u: Float, t: Float, i: Int) {
        val blink = if (sin(t * 0.7f + i * 3) > 0.97f) 0.15f else 1f
        for (ox in floatArrayOf(-u * 0.9f, u * 0.9f)) {
            fill(c("#ffe14d")); oval.set(x + ox - u * 0.6f, y - u * 0.6f * blink, x + ox + u * 0.6f, y + u * 0.6f * blink); cv.drawOval(oval, p)
            fill(Color.BLACK); oval.set(x + ox - u * 0.25f, y - u * 0.25f * blink, x + ox + u * 0.25f, y + u * 0.25f * blink); cv.drawOval(oval, p)
        }
    }

    private fun drawFireflies(cv: Canvas, w: Float, gy: Float, u: Float, t: Float, sx: Float) {
        for (i in 0 until 14) {
            val span = w * 1.2f
            var x = (rnd(i, 21) * w * 2 - sx * 0.8f + sin(t * 0.5f + i) * u * 6) % span
            if (x < 0) x += span
            val y = gy - u * (4 + rnd(i, 22) * 30) + sin(t * 0.9f + i * 2) * u * 3
            val a = max(0f, sin(t * 1.5f + i * 1.7f))
            fill(Color.argb((a * 60).toInt(), 210, 255, 120)); cv.drawCircle(x, y, u * 2, p)
            fill(Color.argb((a * 255).toInt(), 230, 255, 160)); cv.drawCircle(x, y, u * 0.5f, p)
        }
    }

    // ---- helpers ----------------------------------------------------------
    private fun fill(color: Int) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color
    }

    private fun stroke(color: Int, width: Float) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.color = color
        p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND
    }

    companion object {
        private val cache = HashMap<String, Int>()
        fun c(hex: String): Int = cache.getOrPut(hex) { Color.parseColor(hex) }

        fun rnd(i: Int, k: Int): Float {
            val s = sin(i * 127.1 + k * 311.7) * 43758.5453
            return (s - floor(s)).toFloat()
        }
    }
}
