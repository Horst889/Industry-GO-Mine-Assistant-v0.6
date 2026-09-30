package com.example.industrygoassistant

import android.graphics.PointF
import android.graphics.Rect

data class ResourceCandidate(
    val resource: ResourceType,
    val quality: Int,
    val bounds: Rect
)

data class BuildVerification(
    val resource: ResourceType?,
    val quality: Int?,
    val mineDiameterMeters: Int?,
    val noNexusConsumption: Boolean,
    val safeToBuild: Boolean,
    val reason: String
)

data class LocalArea(
    val center: PointF,
    val radiusPx: Float,
    val confidence: Float
)

enum class ResourceType(
    val displayName: String,
    val aliases: List<String>,
    val minQuality: Int,
    val priority: Int
) {
    URANIUM("Uran", listOf("uran", "uranium"), 90, 1),
    RARE_EARTHS("Seltene Erden", listOf("seltene erden", "rare earth", "rare earths"), 90, 2),
    DIAMOND("Diamant", listOf("diamant", "diamond"), 90, 3),
    TITANIUM("Titan", listOf("titan", "titanium"), 90, 4),
    GOLD("Gold", listOf("gold"), 90, 5),
    SILVER("Silber", listOf("silber", "silver"), 90, 6),
    OIL("Öl", listOf("öl", "oel", "oil"), 90, 7),
    COPPER("Kupfer", listOf("kupfer", "copper"), 90, 8),
    COAL("Kohle", listOf("kohle", "coal"), 90, 9),
    IRON("Eisen", listOf("eisen", "iron"), 90, 10),
    STONE("Stein", listOf("stein", "stone"), 90, 11),
    CLAY("Lehm", listOf("lehm", "clay"), 10, 12);

    companion object {
        fun fromText(text: String): ResourceType? {
            val normalized = text.lowercase()
                .replace('ö', 'o')
                .replace('ä', 'a')
                .replace('ü', 'u')
            return entries.firstOrNull { r ->
                r.aliases.any { alias ->
                    val a = alias.lowercase().replace('ö', 'o').replace('ä', 'a').replace('ü', 'u')
                    normalized.contains(a)
                }
            }
        }
    }
}
