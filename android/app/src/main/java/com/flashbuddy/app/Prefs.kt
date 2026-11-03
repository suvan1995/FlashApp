package com.flashbuddy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object Prefs {
    fun photoFile(context: Context) = File(context.filesDir, "main_background.jpg")

    /** Loads the user's "main background" photo, scaled down to roughly screen size. */
    fun loadPhoto(context: Context, maxSide: Int): Bitmap? {
        val f = photoFile(context)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Bumped whenever the photo changes, so the wallpaper reloads it. */
    fun photoVersion(context: Context): Long = photoFile(context).let { if (it.exists()) it.lastModified() else 0L }
}
