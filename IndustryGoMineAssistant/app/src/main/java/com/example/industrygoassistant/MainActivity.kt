package com.example.industrygoassistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var counters: TextView
    private val captureRequest = 4401
    private var startAfterCapture = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val dryRun = findViewById<Switch>(R.id.switchDryRun)
        val localDiameter = findViewById<EditText>(R.id.editLocalDiameter)
        val mineDiameter = findViewById<EditText>(R.id.editMineDiameter)
        val margin = findViewById<EditText>(R.id.editSafetyMargin)
        val probeX = findViewById<EditText>(R.id.editProbeX)
        val probeY = findViewById<EditText>(R.id.editProbeY)
        val buildX = findViewById<EditText>(R.id.editBuildX)
        val buildY = findViewById<EditText>(R.id.editBuildY)
        status = findViewById(R.id.textStatus)
        counters = findViewById(R.id.textCounters)

        dryRun.isChecked = prefs.getBoolean("dryRun", true)
        localDiameter.setText(prefs.getFloat("localDiameterMeters", 96f).toString())
        mineDiameter.setText(prefs.getFloat("targetMineDiameterMeters", 20f).toString())
        margin.setText(prefs.getFloat("safetyMarginMeters", 0.75f).toString())
        probeX.setText(prefs.getFloat("probeX", 0.20f).toString())
        probeY.setText(prefs.getFloat("probeY", 0.83f).toString())
        buildX.setText(prefs.getFloat("buildX", 0.59f).toString())
        buildY.setText(prefs.getFloat("buildY", 0.85f).toString())

        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            prefs.edit()
                .putBoolean("dryRun", dryRun.isChecked)
                .putFloat("localDiameterMeters", localDiameter.text.toString().toFloatOrNull() ?: 96f)
                .putFloat("targetMineDiameterMeters", mineDiameter.text.toString().toFloatOrNull() ?: 20f)
                .putFloat("safetyMarginMeters", margin.text.toString().toFloatOrNull() ?: 0.75f)
                .putFloat("probeX", probeX.text.toString().toFloatOrNull() ?: 0.20f)
                .putFloat("probeY", probeY.text.toString().toFloatOrNull() ?: 0.85f)
                .putFloat("buildX", buildX.text.toString().toFloatOrNull() ?: 0.59f)
                .putFloat("buildY", buildY.text.toString().toFloatOrNull() ?: 0.85f)
                .apply()
            Toast.makeText(this, "Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.buttonAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.buttonStart).setOnClickListener {
            findViewById<Button>(R.id.buttonSave).performClick()
            if (!IndustryGoAutomationService.isConnected()) {
                Toast.makeText(this, "Bitte zuerst die Bedienungshilfe aktivieren.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ScreenCaptureService.isReady()) {
                startAfterCapture = true
                requestScreenCapture()
            } else {
                startAutomationAndOpenGame()
            }
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            IndustryGoAutomationService.requestStop(this)
            refreshStatus()
        }

        refreshStatus()
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status.text = "Status: Bildschirmfreigabe bestätigen"
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Used for Android 8-10 MediaProjection compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            status.text = "Status: Bildschirmfreigabe aktiv – starte Automatik …"
            if (startAfterCapture) {
                startAfterCapture = false
                window.decorView.postDelayed({ startAutomationAndOpenGame() }, 900)
            }
        } else {
            startAfterCapture = false
            status.text = "Status: Bildschirmfreigabe abgelehnt"
            Toast.makeText(this, "Ohne Bildschirmfreigabe kann die App auf Android 8-10 die Karte nicht analysieren.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAutomationAndOpenGame() {
        IndustryGoAutomationService.requestStart(this)
        status.text = "Status: Automatik gestartet – öffne Industry GO"
        val launch = packageManager.getLaunchIntentForPackage("de.industrygo.app")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            Toast.makeText(this, "Industry GO konnte nicht automatisch geöffnet werden. Bitte manuell öffnen.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter("com.example.industrygoassistant.STATUS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshStatus()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        status.text = "Status: ${prefs.getString("status", "bereit")}"
        counters.text = "Gebaut: ${prefs.getInt("successfulBuilds", 0)} | Übersprungen: ${prefs.getInt("skippedPoints", 0)}"
    }
}
