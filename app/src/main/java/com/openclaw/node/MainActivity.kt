package com.openclaw.node

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Main UI for the OpenClaw Node Android app.
 * Provides controls for:
 * - Connecting to the Gateway via WebSocket
 * - Enabling Accessibility Service
 * - Enabling Screen Capture
 * - Viewing connection status and logs
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
    }

    // UI Elements
    private lateinit var connectionStatus: TextView
    private lateinit var gatewayUrlInput: EditText
    private lateinit var bootstrapTokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var screenCaptureButton: Button
    private lateinit var deviceInfoText: TextView
    private lateinit var statusLog: TextView

    private var isConnected = false
    private var accessibilityEnabled = false
    private var screenCaptureEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        connectionStatus = findViewById(R.id.connectionStatus)
        gatewayUrlInput = findViewById(R.id.gatewayUrlInput)
        bootstrapTokenInput = findViewById(R.id.bootstrapTokenInput)
        connectButton = findViewById(R.id.connectButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        screenCaptureButton = findViewById(R.id.screenCaptureButton)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        statusLog = findViewById(R.id.statusLog)

        // Load saved gateway URL and token
        val prefs = getSharedPreferences("openclaw", MODE_PRIVATE)
        gatewayUrlInput.setText(prefs.getString("lastUrl", "ws://192.168.50.233:18789"))
        bootstrapTokenInput.setText(prefs.getString("bootstrapToken", ""))

        // Show device info
        showDeviceInfo()

        // Set up callbacks from service
        GatewayWebSocketService.setCallbacks(
            onStatus = { status ->
                runOnUiThread {
                    connectionStatus.text = status
                    isConnected = status.contains("Connected")
                    connectButton.text = if (isConnected) "Disconnect" else "Connect"
                }
            },
            onLog = { log ->
                runOnUiThread { statusLog.text = log }
            }
        )

        // Connect button
        connectButton.setOnClickListener {
            if (isConnected) {
                disconnectFromGateway()
            } else {
                connectToGateway()
            }
        }

        // Accessibility button
        accessibilityButton.setOnClickListener {
            if (!accessibilityEnabled) {
                requestAccessibilityPermission()
            } else {
                Toast.makeText(this, "Accessibility Service is active", Toast.LENGTH_SHORT).show()
            }
        }

        // Screen Capture button
        screenCaptureButton.setOnClickListener {
            if (!screenCaptureEnabled) {
                requestScreenCapturePermission()
            } else {
                stopScreenCapture()
            }
        }
    }

    private fun showDeviceInfo() {
        val info = """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            ID: ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}
        """.trimIndent()
        deviceInfoText.text = info
    }

    private fun connectToGateway() {
        val url = gatewayUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a Gateway URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Save URL
        getSharedPreferences("openclaw", MODE_PRIVATE)
            .edit().putString("lastUrl", url).apply()

        val token = bootstrapTokenInput.text.toString().trim()
        if (token.isNotEmpty()) {
            getSharedPreferences("openclaw", MODE_PRIVATE)
                .edit().putString("bootstrapToken", token).apply()
        }

        // Start WebSocket service
        val intent = Intent(this, GatewayWebSocketService::class.java).apply {
            action = GatewayWebSocketService.ACTION_CONNECT
            putExtra("gatewayUrl", url)
            putExtra("bootstrapToken", token)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Connecting to $url", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectFromGateway() {
        val intent = Intent(this, GatewayWebSocketService::class.java).apply {
            action = GatewayWebSocketService.ACTION_DISCONNECT
        }
        startService(intent)
        Toast.makeText(this, "Disconnecting", Toast.LENGTH_SHORT).show()
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Toast.makeText(
            this,
            "Please enable 'OpenClaw Node' in Accessibility settings",
            Toast.LENGTH_LONG
        ).show()
        startActivity(intent)
    }

    private fun requestScreenCapturePermission() {
        // Check overlay permission for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                Toast.makeText(this, "Please allow overlay permission", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Request MediaProjection
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startScreenCapture(resultCode, data)
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Settings.canDrawOverlays(this)) {
                    requestScreenCapturePermission()
                } else {
                    Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        // Store intent and result code for the service
        ScreenCaptureService.mediaProjectionResultCode = resultCode
        ScreenCaptureService.mediaProjectionIntent = data

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "START"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        screenCaptureEnabled = true
        screenCaptureButton.text = "Stop Capture"
        Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
    }

    private fun stopScreenCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        screenCaptureEnabled = false
        screenCaptureButton.text = "Screen Capture"
        Toast.makeText(this, "Screen capture stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()

        // Check if accessibility is enabled
        accessibilityEnabled = isAccessibilityServiceEnabled()
        accessibilityButton.text = if (accessibilityEnabled) "Accessibility ✓" else "Accessibility"

        // Check if screen capture is running
        screenCaptureEnabled = ScreenCaptureService::class.java.name.let {
            // We can't easily check this; keep the button state
            screenCaptureEnabled
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/$packageName.NodeAccessibilityService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(service) == true
        } catch (e: Exception) {
            return false
        }
    }

    override fun onDestroy() {
        GatewayWebSocketService.clearCallbacks()
        super.onDestroy()
    }
}
