package com.example.industrygoassistant

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Creates a dense hexagonal grid inside the circular local area.
 * The mine itself must remain completely inside the area, therefore
 * the center is limited to (localRadius - mineRadius).
 */
object DenseCirclePlanner {
    fun plan(
        area: LocalArea,
        localDiameterMeters: Float,
        mineDiameterMeters: Float,
        safetyMarginMeters: Float = 0.75f
    ): List<PointF> {
        if (localDiameterMeters <= 0f || mineDiameterMeters <= 0f) return emptyList()

        val pxPerMeter = (area.radiusPx * 2f) / localDiameterMeters
        val spacingMeters = mineDiameterMeters + safetyMarginMeters
        val dx = spacingMeters * pxPerMeter
        val dy = (sqrt(3f) / 2f) * dx
        val mineRadiusPx = (mineDiameterMeters / 2f) * pxPerMeter
        val usableRadius = area.radiusPx - mineRadiusPx - (safetyMarginMeters * pxPerMeter / 2f)
        if (usableRadius <= 0f) return emptyList()

        val rows = ceil(usableRadius / dy).toInt() + 1
        val cols = ceil(usableRadius / dx).toInt() + 1
        val out = mutableListOf<PointF>()

        for (row in -rows..rows) {
            val y = row * dy
            val offset = if (abs(row) % 2 == 1) dx / 2f else 0f
            for (col in -cols..cols) {
                val x = col * dx + offset
                if (x * x + y * y <= usableRadius * usableRadius) {
                    out += PointF(area.center.x + x, area.center.y + y)
                }
            }
        }

        // Start near the centre and work outwards. This makes the placement stable
        // and reduces the chance of stranding small unusable gaps in the middle.
        return out.sortedBy { p ->
            val x = p.x - area.center.x
            val y = p.y - area.center.y
            x * x + y * y
        }
    }
}
