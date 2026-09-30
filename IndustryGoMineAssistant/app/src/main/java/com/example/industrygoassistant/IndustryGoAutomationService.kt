package com.example.industrygoassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors

class IndustryGoAutomationService : AccessibilityService() {
    companion object {
        private const val TARGET = "de.industrygo.app"
        @Volatile private var instance: IndustryGoAutomationService? = null

        fun requestStart(context: Context) {
            context.getSharedPreferences("config", Context.MODE_PRIVATE)
                .edit().putBoolean("pendingStart", true).apply()
            instance?.startAutomation()
        }

        fun requestStop(context: Context) {
            context.getSharedPreferences("config", Context.MODE_PRIVATE)
                .edit().putBoolean("pendingStart", false).apply()
            instance?.stopAutomation("Manuell gestoppt")
        }

        fun isConnected(): Boolean = instance != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var automationJob: Job? = null
    @Volatile private var running = false
    private val candidatePoints = ArrayDeque<android.graphics.PointF>()
    private var lastArea: LocalArea? = null
    private var successfulBuilds = 0
    private var skippedPoints = 0
    private var pendingPoint: android.graphics.PointF? = null

    override fun onServiceConnected() {
        instance = this
        publishStatus("Bedienungshilfe verbunden")
        if (prefs().getBoolean("pendingStart", false)) startAutomation()
    }

    override fun onDestroy() {
        stopAutomation("Dienst beendet")
        instance = null
        scope.cancel()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = stopAutomation("Bedienungshilfe unterbrochen")

    private fun startAutomation() {
        if (running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ScreenCaptureService.isReady()) {
            publishStatus("Bildschirmfreigabe erforderlich – Mine Assistant erneut öffnen")
            return
        }
        running = true
        prefs().edit().putBoolean("pendingStart", true).apply()
        automationJob = scope.launch { automationLoop() }
        publishStatus("Automatik gestartet – Industry GO öffnen")
    }

    private fun stopAutomation(reason: String) {
        running = false
        automationJob?.cancel()
        automationJob = null
        candidatePoints.clear()
        prefs().edit().putBoolean("pendingStart", false).apply()
        publishStatus(reason)
    }

    private suspend fun automationLoop() {
        while (running) {
            if (rootInActiveWindow?.packageName?.toString() != TARGET) {
                publishStatus("Warte auf Industry GO …")
                delay(700)
                continue
            }

            val mapShot = screenshot()
            if (mapShot == null) {
                publishStatus("Screenshot nicht möglich – Berechtigung prüfen")
                delay(1200)
                continue
            }

            // If an alternative was selected manually or a previous tap succeeded a little late,
            // resume from the ready-to-build state instead of probing a new point again.
            val currentText = OcrAnalyzer.recognize(mapShot)
            val ready = OcrAnalyzer.chooseReadySelection(currentText)
            val resumePoint = pendingPoint
            if (ready != null && resumePoint != null) {
                publishStatus("Bereit: ${ready.resource.displayName} ${ready.quality}% – baue")
                performBuild(ready, resumePoint)
                continue
            }

            if (candidatePoints.isEmpty()) {
                val area = GreenAreaDetector.detect(mapShot)
                if (area == null) {
                    publishStatus("96-m-Bereich nicht sicher erkannt – Zoom/Karte prüfen")
                    delay(1600)
                    continue
                }
                lastArea = area
                val localDiameter = prefs().getFloat("localDiameterMeters", 96f)
                val mineDiameter = prefs().getFloat("targetMineDiameterMeters", 20f)
                val margin = prefs().getFloat("safetyMarginMeters", 0.75f)
                DenseCirclePlanner.plan(area, localDiameter, mineDiameter, margin)
                    .filterNot { ScreenSafety.looksOccupied(mapShot, it) }
                    .forEach { candidatePoints.addLast(it) }
                publishStatus("Raster erkannt: ${candidatePoints.size} freie Kandidaten")
                if (candidatePoints.isEmpty()) {
                    delay(1500)
                    continue
                }
            }

            val point = candidatePoints.removeFirst()
            if (!insideDetectedArea(point)) {
                skippedPoints++
                continue
            }

            publishStatus("Prüfe Rasterpunkt ${successfulBuilds + skippedPoints + 1}")
            pendingPoint = point
            tap(point.x, point.y)
            delay(450)

            // Sondieren
            if (!clickText("Sondieren", "Probe", "Survey")) {
                val shot = screenshot()
                val text = shot?.let { OcrAnalyzer.recognize(it) }
                val probe = OcrAnalyzer.findButtonCenter(text, "Sondieren", "Probe", "Survey")
                if (probe != null) tap(probe.first, probe.second)
                else tapNormalized("probeX", "probeY", 0.20f, 0.83f, shot)
            }
            delay(prefs().getLongCompat("probeWaitMs", 1150L))

            var shot = screenshot() ?: continue
            var text = OcrAnalyzer.recognize(shot)
            val best = OcrAnalyzer.chooseBest(text)
            if (best == null) {
                skippedPoints++
                publishStatus("Keine zulässige Qualität erkannt – Punkt übersprungen")
                closeTransientIfPossible(text)
                delay(350)
                continue
            }

            publishStatus("Beste Auswahl: ${best.resource.displayName} ${best.quality}%")

            // If Industry GO still shows "Keine Quelle", best is an alternative row.
            // Tap the row and explicitly verify that the game changed to the selected
            // resource and now shows "Frei". This prevents the loop from immediately
            // starting a new sondierung when the alternative tap was missed.
            if (OcrAnalyzer.containsNoSource(text)) {
                val selected = selectAlternative(best, shot)
                if (!selected) {
                    // Keep pendingPoint. If the UI applies the selection late (or the user taps it),
                    // the next loop resumes directly at the build step instead of sondieren again.
                    publishStatus("Alternative ${best.resource.displayName} ${best.quality}% noch nicht übernommen – warte")
                    delay(500)
                    continue
                }
            }

            performBuild(best, point)
        }
    }

    private suspend fun selectAlternative(best: ResourceCandidate, initialShot: Bitmap): Boolean {
        var shot = initialShot
        repeat(4) { attempt ->
            // 1) Accessibility first: click a node that contains both resource and quality.
            if (clickCandidateNode(best)) {
                delay(280L + attempt * 80L)
            } else {
                // 2) OCR row target. This is calibrated from the actual Industry GO alternatives list.
                val text = OcrAnalyzer.recognize(shot)
                val target = OcrAnalyzer.exactAlternativeTapPoint(text, best, shot.width)
                    ?: OcrAnalyzer.alternativeTapPoint(best, shot.width)
                tap(target.first, target.second)
                delay(320L + attempt * 100L)
            }

            val updated = screenshot() ?: return@repeat
            shot = updated
            val updatedText = OcrAnalyzer.recognize(updated)
            if (OcrAnalyzer.selectionLooksApplied(updatedText, best)) return true
        }
        return false
    }

    private fun clickCandidateNode(best: ResourceCandidate): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val qualityNeedle = "${best.quality}%"
        val matches = nodes.mapNotNull { n ->
            val value = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ")
            val resourceMatch = best.resource.aliases.any { value.contains(it, ignoreCase = true) }
            val qualityMatch = value.replace(" ", "").contains(qualityNeedle)
            if (resourceMatch && qualityMatch) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r); n to r
            } else null
        }
        val bestNode = matches.maxByOrNull { it.second.centerY() }?.first ?: return false
        return clickNodeOrParent(bestNode)
    }

    private suspend fun performBuild(best: ResourceCandidate, point: android.graphics.PointF) {
        publishStatus("Auswahl aktiv – klicke Bauen")

        var shot = screenshot() ?: return
        var text = OcrAnalyzer.recognize(shot)
        val mainBuild = OcrAnalyzer.findLowestButtonCenter(text, "Bauen", "Build", "Errichten")
        if (mainBuild != null) {
            tap(mainBuild.first, mainBuild.second)
        } else if (!clickLowestText("Bauen", "Build", "Errichten")) {
            // Actual button in the supplied 945x2048 screenshots is centered at ~59% / 85%.
            tapNormalized("buildX", "buildY", 0.59f, 0.85f, shot)
        }

        var verification: BuildVerification? = null
        for (attempt in 0 until 10) {
            delay(280L)
            val dialogShot = screenshot() ?: continue
            shot = dialogShot
            text = OcrAnalyzer.recognize(shot)
            val v = OcrAnalyzer.verifyBuildDialog(text, best)
            if (v.safeToBuild) { verification = v; break }
        }

        val verified = verification
        if (verified == null || !verified.safeToBuild) {
            val reason = OcrAnalyzer.verifyBuildDialog(text, best).reason
            publishStatus("Baudialog nicht freigegeben: $reason")
            return
        }

        val actualDiameter = verified.mineDiameterMeters?.toFloat() ?: prefs().getFloat("targetMineDiameterMeters", 20f)
        if (!insideDetectedAreaForDiameter(point, actualDiameter)) {
            skippedPoints++
            publishStatus("Nicht gebaut: ${actualDiameter.toInt()}-m-Mine würde 96-m-Bereich überschreiten")
            clickText("Abbrechen", "Cancel")
            pendingPoint = null
            candidatePoints.clear()
            return
        }
        prefs().edit().putFloat("targetMineDiameterMeters", actualDiameter).apply()

        if (prefs().getBoolean("dryRun", true)) {
            publishStatus("TEST: ${best.resource.displayName} ${best.quality}% – ${verified.mineDiameterMeters ?: "?"} m, ohne Nexus")
            clickText("Abbrechen", "Cancel")
            pendingPoint = null
            delay(300)
            return
        }

        // Final dialog: the title and button both say BAUEN. Use lowest OCR occurrence,
        // then accessibility, then a calibrated dialog-button fallback (~68% / 67%).
        val finalBuild = OcrAnalyzer.findLowestButtonCenter(text, "Bauen", "Build")
        when {
            finalBuild != null -> tap(finalBuild.first, finalBuild.second)
            clickLowestText("Bauen", "Build") -> Unit
            else -> tap(shot.width * 0.68f, shot.height * 0.67f)
        }

        // Poll for success; on slow mobile data the dialog may stay visible for a while.
        var success = false
        repeat(10) { attempt ->
            delay(if (attempt == 0) 650L else 320L)
            val resultShot = screenshot() ?: return@repeat
            val resultText = OcrAnalyzer.recognize(resultShot)
            if (OcrAnalyzer.isBuildSuccess(resultText)) { success = true; return@repeat }
            if (attempt == 3 && OcrAnalyzer.verifyBuildDialog(resultText, best).safeToBuild) {
                OcrAnalyzer.findLowestButtonCenter(resultText, "Bauen", "Build")?.let { tap(it.first, it.second) }
                    ?: tap(resultShot.width * 0.68f, resultShot.height * 0.67f)
            }
        }

        if (success) {
            successfulBuilds++
            publishStatus("Mine #$successfulBuilds gebaut: ${best.resource.displayName} ${best.quality}%")
        } else {
            skippedPoints++
            publishStatus("Bau nicht bestätigt – Karte wird neu geprüft")
        }
        pendingPoint = null
        candidatePoints.clear()
        delay(450)
    }

    private fun insideDetectedArea(p: android.graphics.PointF): Boolean =
        insideDetectedAreaForDiameter(p, prefs().getFloat("targetMineDiameterMeters", 20f))

    private fun insideDetectedAreaForDiameter(p: android.graphics.PointF, mineDiameter: Float): Boolean {
        val area = lastArea ?: return false
        val localDiameter = prefs().getFloat("localDiameterMeters", 96f)
        val pxPerMeter = area.radiusPx * 2f / localDiameter
        val margin = prefs().getFloat("safetyMarginMeters", 0.75f)
        val allowed = area.radiusPx - ((mineDiameter / 2f) + margin / 2f) * pxPerMeter
        val dx = p.x - area.center.x
        val dy = p.y - area.center.y
        return dx * dx + dy * dy <= allowed * allowed
    }

    private fun clickText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val match = nodes.firstOrNull { n ->
            val value = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ")
            labels.any { value.contains(it, ignoreCase = true) }
        } ?: return false
        return clickNodeOrParent(match)
    }

    private fun clickLowestText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val matches = nodes.mapNotNull { n ->
            val value = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ")
            if (labels.any { value.equals(it, ignoreCase = true) || value.contains(it, ignoreCase = true) }) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r); n to r
            } else null
        }
        val best = matches.maxByOrNull { it.second.centerY() }?.first ?: return false
        return clickNodeOrParent(best)
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNodes(it, out) }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            if (n?.isClickable == true && n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            n = n?.parent
        }
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            tap(rect.exactCenterX(), rect.exactCenterY())
            return true
        }
        return false
    }

    private suspend fun closeTransientIfPossible(text: com.google.mlkit.vision.text.Text?) {
        OcrAnalyzer.findButtonCenter(text, "Abbrechen", "Cancel", "Schließen", "Close")?.let { tap(it.first, it.second) }
    }

    private fun tapNormalized(xKey: String, yKey: String, dx: Float, dy: Float, bitmap: Bitmap?) {
        if (bitmap == null) return
        val x = prefs().getFloat(xKey, dx).coerceIn(0f, 1f) * bitmap.width
        val y = prefs().getFloat(yKey, dy).coerceIn(0f, 1f) * bitmap.height
        tap(x, y)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 75))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val bitmap = ScreenCaptureService.latestBitmap()
            if (cont.isActive) cont.resume(bitmap) {}
            return@suspendCancellableCoroutine
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (cont.isActive) cont.resume(copy) {}
                }
                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        )
    }

    private fun prefs() = getSharedPreferences("config", MODE_PRIVATE)

    private fun publishStatus(message: String) {
        Log.i("IGOA", message)
        prefs().edit()
            .putString("status", message)
            .putInt("successfulBuilds", successfulBuilds)
            .putInt("skippedPoints", skippedPoints)
            .apply()
        sendBroadcast(android.content.Intent("com.example.industrygoassistant.STATUS").setPackage(packageName))
    }

    private fun android.content.SharedPreferences.getLongCompat(key: String, defaultValue: Long): Long {
        return try { getLong(key, defaultValue) } catch (_: ClassCastException) { defaultValue }
    }
}
