package com.example.industrygoassistant

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.max

object GreenGridPlanner {
    /**
     * Generates a dense square grid and picks the offset that yields the most points
     * inside the detected green survey region. The green test is intentionally broad
     * and can be tuned later from screenshots of the real device.
     */
    fun plan(bitmap: Bitmap, spacing: Int): List<PointF> {
        val s = max(30, spacing)
        var best: List<PointF> = emptyList()
        val step = max(4, s / 10)

        for (ox in 0 until s step step) {
            for (oy in 0 until s step step) {
                val current = ArrayList<PointF>()
                var y = oy + s / 2
                while (y < bitmap.height - s / 2) {
                    var x = ox + s / 2
                    while (x < bitmap.width - s / 2) {
                        if (isSafeGreenCell(bitmap, x, y, (s * 0.34f).toInt())) {
                            current.add(PointF(x.toFloat(), y.toFloat()))
                        }
                        x += s
                    }
                    y += s
                }
                if (current.size > best.size) best = current
            }
        }
        return best
    }

    private fun isSafeGreenCell(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Boolean {
        val samples = arrayOf(
            intArrayOf(cx, cy),
            intArrayOf(cx - radius, cy),
            intArrayOf(cx + radius, cy),
            intArrayOf(cx, cy - radius),
            intArrayOf(cx, cy + radius),
            intArrayOf(cx - radius, cy - radius),
            intArrayOf(cx + radius, cy - radius),
            intArrayOf(cx - radius, cy + radius),
            intArrayOf(cx + radius, cy + radius)
        )
        return samples.all { (x, y) ->
            x in 0 until bitmap.width && y in 0 until bitmap.height && isGreen(bitmap.getPixel(x, y))
        }
    }

    private fun isGreen(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return g > 70 && g > r * 1.15 && g > b * 1.08 && (g - r) > 15
    }
}
