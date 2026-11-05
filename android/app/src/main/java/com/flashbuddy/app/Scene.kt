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
        drawCharacter(cv, cx, footY, u, t, isNight)
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

    // ---- character: a shih tzu standing on its hind legs ----------------
    // Ported from web/scene.js. Geometry in "u" units, origin at the feet, facing right.
    private class Hand(val x: Float, val y: Float, val dir: Float)

    private fun bob(u: Float) = abs(sin(walkPhase)) * u * 0.6f

    private fun hand(cx: Float, footY: Float, u: Float): Hand {
        val shX = u * 1.6f; val shY = -u * 10.6f - bob(u)
        val len = u * 8.6f
        val lx = shX + cos(beamAngle) * len; val ly = shY + sin(beamAngle) * len
        val s = if (turn == 0f) 1f else sign(turn)
        return Hand(cx + lx * turn, footY + ly, atan2(sin(beamAngle), cos(beamAngle) * s))
    }

    private fun ell(cv: Canvas, x: Float, y: Float, rx: Float, ry: Float, rot: Float = 0f) {
        if (rot != 0f) { cv.save(); cv.rotate(Math.toDegrees(rot.toDouble()).toFloat(), x, y) }
        oval.set(x - rx, y - ry, x + rx, y + ry); cv.drawOval(oval, p)
        if (rot != 0f) cv.restore()
    }

    private fun drawCharacter(cv: Canvas, cx: Float, footY: Float, u: Float, t: Float, dim: Boolean) {
        val swing = sin(walkPhase) * 0.45f
        val bob = bob(u)
        val fur = c(if (dim) "#ddd3c2" else "#f6eee0")
        val furShade = c(if (dim) "#c4b8a4" else "#e2d6c2")
        val tan = c(if (dim) "#b5844f" else "#cf9a5f")
        val ear = c(if (dim) "#33241c" else "#3f2c22")
        val earTop = c(if (dim) "#8f6640" else "#a97a4c")
        val collar = c(if (dim) "#d65a1a" else "#f26b1d")
        val collar2 = c(if (dim) "#e0b52e" else "#ffd23f")
        val collar3 = c(if (dim) "#b8322c" else "#d9402f")
        val metal = c(if (dim) "#a9adb3" else "#c9ccd1")
        val tika = c(if (dim) "#b8394c" else "#d9455c")

        cv.save()
        cv.translate(cx, footY)
        cv.scale(if (abs(turn) < 0.02f) 0.02f * sign(turn + 1e-4f) else turn, 1f)

        fill(Color.argb(64, 0, 0, 0)); ell(cv, 0f, 0f, u * 5, u)
        cv.translate(0f, -bob)

        // tail: a fluffy plume that wags
        val wag = sin(t * 9) * 0.35f
        cv.save()
        cv.translate(-u * 3.2f, -u * 7.5f); cv.rotate(Math.toDegrees((-0.25f + wag).toDouble()).toFloat())
        for (k in 0 until 6) {
            fill(if (k > 3) tan else furShade)
            ell(cv, -u * k * 0.35f + u * k * k * 0.06f, -u * k * 1.05f, u * (1.5f - k * 0.1f), u * (1.3f - k * 0.08f))
        }
        cv.restore()

        fun leg(ang: Float, color: Int) {
            cv.save(); cv.translate(u * 0.2f, -u * 4.6f); cv.rotate(Math.toDegrees(-ang.toDouble()).toFloat())
            fill(color)
            oval.set(-u * 1.5f, 0f, u * 1.5f, u * 4.6f); cv.drawRoundRect(oval, u * 1.4f, u * 1.4f, p)
            ell(cv, u * 0.5f, u * 4.3f, u * 1.9f, u * 1.0f)
            cv.restore()
        }
        leg(-swing, furShade)
        stroke(furShade, u * 2.2f)
        cv.drawLine(-u * 0.6f, -u * 10.4f, -u * 0.6f - sin(swing) * u * 3, -u * 7.2f, p)
        leg(swing, fur)

        // body
        fill(fur); ell(cv, 0f, -u * 8.2f, u * 4.4f, u * 4.9f)
        fill(tan); ell(cv, -u * 2.6f, -u * 9.6f, u * 1.8f, u * 2.6f, 0.3f)
        fill(fur)
        for (k in -3..3) ell(cv, k * u * 1.2f, -u * 3.9f + abs(k) * u * 0.25f, u * 1.0f, u * 0.9f)

        // collar (braided, with a ring and a bell)
        cv.save()
        cv.translate(u * 0.9f, -u * 12.4f); cv.rotate(Math.toDegrees(0.12).toFloat())
        fill(collar); oval.set(-u * 3.6f, -u * 0.65f, u * 3.6f, u * 0.65f); cv.drawRoundRect(oval, u * 0.6f, u * 0.6f, p)
        stroke(collar3, u * 0.25f)
        for (k in -3..3) cv.drawLine(k * u - u * 0.3f, -u * 0.5f, k * u + u * 0.3f, u * 0.5f, p)
        fill(collar2); cv.drawRect(-u * 0.9f, -u * 0.7f, -u * 0.1f, u * 0.7f, p)
        stroke(metal, u * 0.3f); cv.drawCircle(u * 1.6f, u * 1.4f, u * 0.75f, p)
        fill(c("#d4a93a")); cv.drawCircle(u * 2.9f, u * 1.0f, u * 0.45f, p)
        fill(collar); cv.drawRect(u * 3.2f, u * 0.3f, u * 3.45f, u * 1.4f, p); cv.drawRect(u * 3.6f, u * 0.3f, u * 3.85f, u * 1.3f, p)
        cv.restore()

        // head (3/4 view, looking right)
        val hx = u * 1.6f; val hy = -u * 17.2f
        fill(ear); ell(cv, hx + u * 4.6f, hy + u * 1.8f, u * 1.6f, u * 3.6f, -0.25f)
        fill(fur); ell(cv, hx, hy, u * 5.2f, u * 4.9f)
        for (k in 0 until 5) ell(cv, hx - u * 2 + k * u, hy - u * 4.6f + abs(k - 2) * u * 0.3f, u * 0.9f, u * 0.7f)
        fill(tan)
        ell(cv, hx + u * 0.4f, hy - u * 0.6f, u * 2.3f, u * 2.0f, -0.2f)
        ell(cv, hx + u * 3.5f, hy - u * 0.8f, u * 1.7f, u * 1.8f, 0.2f)
        fill(tika); ell(cv, hx + u * 1.9f, hy - u * 3.3f, u * 0.55f, u * 0.8f)
        fill(earTop); ell(cv, hx - u * 3.6f, hy + u * 0.2f, u * 2.0f, u * 2.6f, 0.2f)
        fill(ear); ell(cv, hx - u * 3.9f, hy + u * 2.4f, u * 1.9f, u * 3.4f, 0.15f)
        val ex = cos(beamAngle) * u * 0.35f; val ey = sin(beamAngle) * u * 0.35f
        for ((ox, r) in listOf(u * 0.6f to 1.25f, u * 3.6f to 1.05f)) {
            val x = hx + ox; val y = hy - u * 0.6f
            fill(c("#fbf6ee")); ell(cv, x, y, u * r * 1.05f, u * r * 1.1f)
            fill(c("#5a3420")); ell(cv, x + ex, y + ey, u * r * 0.85f, u * r * 0.9f)
            fill(c("#120c0a")); ell(cv, x + ex, y + ey, u * r * 0.45f, u * r * 0.5f)
            fill(Color.WHITE); ell(cv, x + ex + u * r * 0.3f, y + ey - u * r * 0.35f, u * r * 0.22f, u * r * 0.22f)
        }
        fill(fur)
        ell(cv, hx + u * 2.5f, hy + u * 2.3f, u * 2.6f, u * 2.0f)
        ell(cv, hx + u * 2.3f, hy + u * 3.9f, u * 2.2f, u * 1.4f)
        stroke(furShade, u * 0.3f)
        path.reset(); path.moveTo(hx + u * 1.3f, hy + u * 3.4f); path.quadTo(hx + u * 2.4f, hy + u * 2.8f, hx + u * 3.5f, hy + u * 3.4f)
        cv.drawPath(path, p)
        fill(c("#161214")); ell(cv, hx + u * 2.5f, hy + u * 1.4f, u * 1.15f, u * 0.85f)
        fill(Color.argb(89, 255, 255, 255)); ell(cv, hx + u * 2.2f, hy + u * 1.1f, u * 0.35f, u * 0.2f)

        // front paw holding the flashlight at beamAngle
        val shX = u * 1.6f; val shY = -u * 10.6f
        val handX = shX + cos(beamAngle) * u * 4.2f; val handY = shY + sin(beamAngle) * u * 4.2f
        stroke(fur, u * 2.3f); cv.drawLine(shX, shY, handX, handY, p)
        cv.save()
        cv.translate(handX, handY); cv.rotate(Math.toDegrees(beamAngle.toDouble()).toFloat())
        fill(c("#4a5568")); cv.drawRect(-u * 0.5f, -u * 0.65f, u * 2.5f, u * 0.65f, p)
        fill(c("#718096")); cv.drawRect(u * 2.4f, -u * 1.0f, u * 3.8f, u * 1.0f, p)
        fill(c(if (dim && night > 0.5f) "#fffbe0" else "#cbd5e0")); cv.drawRect(u * 3.7f, -u * 0.9f, u * 4.1f, u * 0.9f, p)
        cv.restore()
        fill(fur); ell(cv, handX, handY, u * 1.25f, u * 1.15f)
        stroke(furShade, u * 0.2f)
        for (d in floatArrayOf(-0.4f, 0f, 0.4f)) cv.drawLine(handX + u * 0.6f, handY + d * u, handX + u * 1.1f, handY + d * u, p)
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
