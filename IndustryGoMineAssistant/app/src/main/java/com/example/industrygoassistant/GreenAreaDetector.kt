package com.example.industrygoassistant

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Detects the 96 m green local/probing circle from a screenshot.
 * Strategy:
 *  1. find the red location pin as the likely centre;
 *  2. score concentric radii for a continuous green circumference;
 *  3. fall back to the map centre if the pin cannot be found.
 *
 * It intentionally ignores the smaller green rings around existing mines by
 * requiring a high fraction of the full circumference to look green.
 */
object GreenAreaDetector {
    fun detect(bitmap: Bitmap): LocalArea? {
        val mapTop = (bitmap.height * 0.26f).toInt()
        val mapBottom = (bitmap.height * 0.88f).toInt()
        val center = detectRedPin(bitmap, mapTop, mapBottom)
            ?: PointF(bitmap.width * 0.49f, bitmap.height * 0.59f)

        val minDim = min(bitmap.width, mapBottom - mapTop).toFloat()
        val minRadius = max(90f, minDim * 0.20f)
        val maxRadius = min(minDim * 0.52f, bitmap.width * 0.48f)
        if (maxRadius <= minRadius) return null

        var bestRadius = 0f
        var bestScore = 0f
        var r = minRadius
        while (r <= maxRadius) {
            val score = circleGreenScore(bitmap, center, r, mapTop, mapBottom)
            if (score > bestScore) {
                bestScore = score
                bestRadius = r
            }
            r += 3f
        }

        // The screenshot supplied by the user shows a clear circle; in live use
        // we still reject weak detections rather than tapping outside the area.
        return if (bestScore >= 0.18f) LocalArea(center, bestRadius, bestScore) else null
    }

    private fun detectRedPin(bitmap: Bitmap, top: Int, bottom: Int): PointF? {
        var sx = 0.0
        var sy = 0.0
        var count = 0
        val left = (bitmap.width * 0.15f).toInt()
        val right = (bitmap.width * 0.85f).toInt()
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // Industry GO's location pin is a saturated coral/red.
                if (r > 210 && g in 45..150 && b in 45..150 && r > g + 75 && r > b + 70) {
                    sx += x
                    sy += y
                    count++
                }
            }
        }
        return if (count >= 20) PointF((sx / count).toFloat(), (sy / count).toFloat()) else null
    }

    private fun circleGreenScore(
        bitmap: Bitmap,
        center: PointF,
        radius: Float,
        top: Int,
        bottom: Int
    ): Float {
        var hits = 0
        var valid = 0
        val samples = 120
        for (i in 0 until samples) {
            val a = 2.0 * PI * i / samples
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            var localHit = false
            for (dr in -4..4) {
                val rr = radius + dr
                val x = (center.x + ux * rr).toInt()
                val y = (center.y + uy * rr).toInt()
                if (x !in 0 until bitmap.width || y !in top until bottom) continue
                valid++
                if (isGreen(bitmap.getPixel(x, y))) {
                    localHit = true
                    break
                }
            }
            if (localHit) hits++
        }
        return if (valid == 0) 0f else hits.toFloat() / samples.toFloat()
    }

    private fun isGreen(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return g >= 120 && g >= r + 18 && g >= b + 8
    }
}
