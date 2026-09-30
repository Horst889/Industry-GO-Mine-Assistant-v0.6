package com.example.industrygoassistant

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.max

object ScreenSafety {
    /**
     * Conservative pre-check for a candidate. Existing mine icons are usually
     * dark circular objects with coloured rings. We only skip very dark centres;
     * the game itself remains the final authority on whether a position is valid.
     */
    fun looksOccupied(bitmap: Bitmap, p: PointF): Boolean {
        val rr = max(8, (bitmap.width * 0.014f).toInt())
        var dark = 0
        var total = 0
        for (dy in -rr..rr step 2) {
            for (dx in -rr..rr step 2) {
                if (dx * dx + dy * dy > rr * rr) continue
                val x = p.x.toInt() + dx
                val y = p.y.toInt() + dy
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
                val c = bitmap.getPixel(x, y)
                val lum = (Color.red(c) * 0.21 + Color.green(c) * 0.72 + Color.blue(c) * 0.07)
                if (lum < 72) dark++
                total++
            }
        }
        return total > 0 && dark.toFloat() / total > 0.58f
    }
}
