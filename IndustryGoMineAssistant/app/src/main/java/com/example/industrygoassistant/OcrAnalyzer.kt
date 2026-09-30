package com.example.industrygoassistant

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

object OcrAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val percentRegex = Regex("(100|[1-9]?[0-9])\\s*%")
    private val meterRegex = Regex("(?:Minengr[oö]ße|Mine size)\\s*:?\\s*(\\d{1,3})\\s*m", RegexOption.IGNORE_CASE)
    private val qualityLabelRegex = Regex("(?:Qualit[aä]t|Quality)\\s*:?\\s*(100|[1-9]?[0-9])\\s*%", RegexOption.IGNORE_CASE)

    suspend fun recognize(bitmap: android.graphics.Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /**
     * Reads both the primary sondierung result and the three alternative rows.
     * Resource priority is always stronger than percentage, but each resource must
     * still meet its configured minimum quality.
     *
     * Example: Uran 0 %, alternatives Kohle 99 %, Lehm 97 %, Eisen 97 %
     * -> Kohle 99 %.
     */
    fun chooseBest(text: Text?): ResourceCandidate? {
        if (text == null) return null
        val lines = text.textBlocks.flatMap { it.lines }
        val alternativesY = lines.firstOrNull {
            it.text.contains("Alternativen", ignoreCase = true) || it.text.contains("Alternatives", ignoreCase = true)
        }?.boundingBox?.centerY()

        val candidates = mutableListOf<ResourceCandidate>()
        for (line in lines) {
            val bounds = line.boundingBox ?: continue
            val resource = ResourceType.fromText(line.text) ?: continue

            // Do not interpret labels from the top filter/navigation as a result.
            // Relevant resource labels are either close to a percentage/quality
            // result or located in the alternatives section.
            val ownQuality = percentRegex.find(line.text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val nearbyQuality = nearestPercent(bounds, lines)
            val quality = ownQuality ?: nearbyQuality ?: continue

            val inAlternatives = alternativesY != null && bounds.centerY() > alternativesY
            val closeToPercent = nearestPercentDistanceSquared(bounds, lines)?.let { it < 190 * 190 } == true
            if (!inAlternatives && !closeToPercent) continue

            if (quality >= resource.minQuality) {
                candidates += ResourceCandidate(resource, quality, bounds)
            }
        }

        return candidates
            .distinctBy { Triple(it.resource, it.quality, it.bounds.centerY()) }
            .sortedWith(compareBy<ResourceCandidate> { it.resource.priority }.thenByDescending { it.quality })
            .firstOrNull()
    }


    /** Detects an already selected, buildable result on the map (e.g. Kohle 99% + Frei). */
    fun chooseReadySelection(text: Text?): ResourceCandidate? {
        if (text == null) return null
        val raw = text.text
        if (!(raw.contains("Frei", ignoreCase = true) || raw.contains("Free", ignoreCase = true))) return null
        if (containsNoSource(text)) return null

        val lines = text.textBlocks.flatMap { it.lines }
        val candidates = mutableListOf<ResourceCandidate>()
        for (line in lines) {
            val bounds = line.boundingBox ?: continue
            val resource = ResourceType.fromText(line.text) ?: continue
            val ownQuality = percentRegex.find(line.text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val quality = ownQuality ?: nearestPercent(bounds, lines) ?: continue
            if (quality >= resource.minQuality) candidates += ResourceCandidate(resource, quality, bounds)
        }
        return candidates.sortedWith(compareBy<ResourceCandidate> { it.resource.priority }.thenByDescending { it.quality }).firstOrNull()
    }

    /** Returns the OCR row that most likely belongs to the requested alternative. */
    fun exactAlternativeTapPoint(text: Text?, candidate: ResourceCandidate, screenWidth: Int): Pair<Float, Float>? {
        if (text == null) return null
        val lines = text.textBlocks.flatMap { it.lines }
        val altY = lines.firstOrNull {
            it.text.contains("Alternativen", ignoreCase = true) || it.text.contains("Alternatives", ignoreCase = true)
        }?.boundingBox?.centerY() ?: return null

        val match = lines.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            if (b.centerY() <= altY) return@mapNotNull null
            val r = ResourceType.fromText(line.text) ?: return@mapNotNull null
            val q = percentRegex.find(line.text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (r == candidate.resource && q == candidate.quality) line to b else null
        }.firstOrNull() ?: return null

        val b = match.second
        val x = maxOf(b.exactCenterX(), screenWidth * 0.60f)
        return x to b.exactCenterY()
    }

    /** True once Industry GO has accepted the chosen alternative and shows it as free. */
    fun selectionLooksApplied(text: Text?, expected: ResourceCandidate): Boolean {
        val raw = text?.text ?: return false
        val resourcePresent = expected.resource.aliases.any { raw.contains(it, ignoreCase = true) }
        val qualityPresent = Regex("${expected.quality}\\s*%").containsMatchIn(raw)
        val free = raw.contains("Frei", ignoreCase = true) || raw.contains("Free", ignoreCase = true)
        val noSource = raw.contains("Keine Quelle", ignoreCase = true) || raw.contains("No source", ignoreCase = true)
        return resourcePresent && qualityPresent && free && !noSource
    }

    fun verifyBuildDialog(text: Text?, expected: ResourceCandidate): BuildVerification {
        if (text == null) return BuildVerification(null, null, null, false, false, "Kein Text erkannt")
        val raw = text.text

        // Prefer the explicit "Gebäude: X-Mine" content of the dialog. This avoids
        // confusing background map labels with the mine that is about to be built.
        val dialogResource = ResourceType.entries.firstOrNull { r ->
            r.aliases.any { alias ->
                raw.contains("$alias-Mine", ignoreCase = true) ||
                    raw.contains("$alias Mine", ignoreCase = true)
            }
        } ?: ResourceType.fromText(raw)

        val quality = qualityLabelRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val mineSize = meterRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val noNexus = raw.contains("kein Nexus-Verbrauch", ignoreCase = true) ||
            raw.contains("no nexus", ignoreCase = true) ||
            raw.contains("without nexus", ignoreCase = true)
        val titleLooksLikeBuild = raw.contains("BAUEN", ignoreCase = true) || raw.contains("BUILD", ignoreCase = true)
        val expectedResource = dialogResource == expected.resource
        val qualityMatches = quality == null || quality == expected.quality
        val safe = titleLooksLikeBuild && expectedResource && qualityMatches && noNexus
        val reason = when {
            !titleLooksLikeBuild -> "Baudialog nicht sicher erkannt"
            !expectedResource -> "Rohstoff im Baudialog stimmt nicht"
            !qualityMatches -> "Qualität im Baudialog stimmt nicht"
            !noNexus -> "'kein Nexus-Verbrauch' nicht erkannt"
            else -> "OK"
        }
        return BuildVerification(dialogResource, quality, mineSize, noNexus, safe, reason)
    }

    fun isBuildSuccess(text: Text?): Boolean {
        val t = text?.text ?: return false
        return t.contains("Mine gebaut", ignoreCase = true) ||
            t.contains("erfolgreich gebaut", ignoreCase = true) ||
            t.contains("mine built", ignoreCase = true)
    }

    fun containsNoSource(text: Text?): Boolean {
        val t = text?.text ?: return false
        return t.contains("Keine Quelle", ignoreCase = true) || t.contains("No source", ignoreCase = true)
    }

    fun findButtonCenter(text: Text?, vararg labels: String): Pair<Float, Float>? {
        if (text == null) return null
        for (block in text.textBlocks) {
            for (line in block.lines) {
                if (labels.any { line.text.contains(it, ignoreCase = true) }) {
                    val b = line.boundingBox ?: continue
                    return b.exactCenterX() to b.exactCenterY()
                }
            }
        }
        return null
    }

    /** Finds the lowest matching OCR line. Useful for the bottom Bauen button. */
    fun findLowestButtonCenter(text: Text?, vararg labels: String): Pair<Float, Float>? {
        if (text == null) return null
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            if (!labels.any { line.text.contains(it, ignoreCase = true) }) return@mapNotNull null
            val b = line.boundingBox ?: return@mapNotNull null
            Triple(b.centerY(), b.exactCenterX(), b.exactCenterY())
        }.maxByOrNull { it.first }?.let { it.second to it.third }
    }

    /** Tap target for an alternative row. The row itself is wider than OCR text,
     * so use a stable x-position while preserving the OCR-derived row y-position. */
    fun alternativeTapPoint(candidate: ResourceCandidate, screenWidth: Int): Pair<Float, Float> {
        val x = (screenWidth * 0.66f).coerceAtLeast(candidate.bounds.exactCenterX())
        return x to candidate.bounds.exactCenterY()
    }

    private fun nearestPercent(target: Rect, lines: List<Text.Line>): Int? {
        return nearestPercentWithDistance(target, lines)?.second
    }

    private fun nearestPercentDistanceSquared(target: Rect, lines: List<Text.Line>): Int? {
        return nearestPercentWithDistance(target, lines)?.first
    }

    private fun nearestPercentWithDistance(target: Rect, lines: List<Text.Line>): Pair<Int, Int>? {
        return lines.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            val q = percentRegex.find(line.text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null

            // Prefer values on the same visual row. This avoids accidentally using
            // the phone's battery percentage or Industry GO's XP percentage.
            val dy = abs(b.centerY() - target.centerY())
            if (dy > 90) return@mapNotNull null
            val dx = b.centerX() - target.centerX()
            val d2 = dx * dx + dy * dy
            Triple(d2, q, b)
        }.minByOrNull { it.first }?.let { it.first to it.second }
    }
}
