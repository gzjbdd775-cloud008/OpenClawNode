package com.openclaw.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground WebSocket service that connects to the OpenClaw Gateway.
 * Implements the Gateway WS protocol (v3): challenge → connect → invoke/ping cycle.
 */
class GatewayWebSocketService : Service() {

    companion object {
        const val TAG = "GatewayWS"
        const val CHANNEL_ID = "gateway_connection"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "com.openclaw.node.CONNECT"
        const val ACTION_DISCONNECT = "com.openclaw.node.DISCONNECT"
        const val ACTION_SEND_INVOKE_RESULT = "com.openclaw.node.SEND_INVOKE_RESULT"

        // Binder for Activity communication
        var currentStatus: String = "Disconnected"
            private set
        var currentLog: String = ""
            private set
        private var statusCallback: ((String) -> Unit)? = null
        private var logCallback: ((String) -> Unit)? = null

        fun setCallbacks(onStatus: (String) -> Unit, onLog: (String) -> Unit) {
            statusCallback = onStatus
            logCallback = onLog
        }

        fun clearCallbacks() {
            statusCallback = null
            logCallback = null
        }
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val requestIdCounter = AtomicInteger(0)
    private var gatewayUrl: String = ""
    private var deviceToken: String? = null
    private var connected = false
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 30000L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                gatewayUrl = intent.getStringExtra("gatewayUrl") ?: return START_NOT_STICKY
                val token = intent.getStringExtra("bootstrapToken") ?: ""
                connect(gatewayUrl, token)
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_SEND_INVOKE_RESULT -> {
                val invokeId = intent.getStringExtra("invokeId") ?: ""
                val resultJson = intent.getStringExtra("result") ?: "{}"
                sendInvokeResult(invokeId, resultJson)
            }
        }
        return START_NOT_STICKY
    }

    private fun connect(url: String, token: String) {
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        updateStatus("Connecting...")
        appendLog("Connecting to $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                appendLog("WebSocket opened")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(ws, text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                appendLog("WebSocket closing: $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                appendLog("WebSocket closed: $reason")
                connected = false
                updateStatus("Disconnected")
                updateNotification("Disconnected")
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                appendLog("WebSocket error: ${t.message}")
                connected = false
                updateStatus("Error: ${t.message}")
                updateNotification("Error")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            val type = msg.optString("type")

            when (type) {
                "event" -> handleEvent(ws, msg)
                "res" -> handleResponse(msg)
                "invoke" -> handleInvoke(ws, msg)
                "ping" -> {
                    ws.send("{\"type\":\"pong\"}")
                }
            }
        } catch (e: Exception) {
            appendLog("Parse error: ${e.message}")
        }
    }

    private fun handleEvent(ws: WebSocket, msg: JSONObject) {
        val event = msg.optString("event")
        when (event) {
            "connect.challenge" -> {
                val nonce = msg.getJSONObject("payload").optString("nonce", "")
                sendConnect(ws, nonce)
            }
            "tick" -> {
                // Keepalive tick, no action needed
            }
            "shutdown" -> {
                appendLog("Gateway shutting down")
                disconnect()
            }
        }
    }

    private fun sendConnect(ws: WebSocket, nonce: String) {
        val deviceId = Settings.Secure.getString(
            contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "android-unknown"

        val connectReq = JSONObject().apply {
            put("type", "req")
            put("id", "connect-${requestIdCounter.incrementAndGet()}")
            put("method", "connect")
            put("params", JSONObject().apply {
                put("minProtocol", 3)
                put("maxProtocol", 3)
                put("client", JSONObject().apply {
                    put("id", "android-node")
                    put("version", "1.0.0")
                    put("platform", "android")
                    put("mode", "node")
                })
                put("role", "node")
                put("scopes", JSONArray())
                put("caps", JSONArray(listOf("screen", "camera", "canvas", "location")))
                put("commands", JSONArray(listOf(
                    "canvas.navigate", "canvas.snapshot", "canvas.eval",
                    "system.run", "system.which", "screen.record",
                    "camera.snap", "camera.clip", "location.get",
                    "contacts.search", "contacts.add",
                    "notifications.list", "sms.search", "callLog.search",
                    "device.status", "device.info", "device.permissions", "device.health"
                )))
                put("permissions", JSONObject())
                put("auth", JSONObject().apply {
                    val token = deviceToken
                    if (token != null) {
                        put("deviceToken", token)
                    } else {
                        // Use bootstrap token for first-time pairing
                    }
                })
                put("locale", "zh-CN")
                put("userAgent", "openclaw-android/1.0.0")
                put("device", JSONObject().apply {
                    put("id", deviceId)
                    put("nonce", nonce)
                })
            })
        }

        appendLog("Sending connect request...")
        ws.send(connectReq.toString())
    }

    private fun handleResponse(msg: JSONObject) {
        val method = msg.optString("method", "")
        val id = msg.optString("id", "")
        val ok = msg.optBoolean("ok", false)

        if (id.startsWith("connect-")) {
            if (ok) {
                val payload = msg.optJSONObject("payload")
                connected = true
                reconnectAttempts = 0
                updateStatus("Connected ✓")
                updateNotification("Connected")
                appendLog("Successfully connected to Gateway!")

                // Save device token if provided
                payload?.let { p ->
                    val auth = p.optJSONObject("auth")
                    auth?.let { a ->
                        val token = a.optString("deviceToken", "")
                        if (token.isNotEmpty()) {
                            deviceToken = token
                            appendLog("Device token received")
                            // Save token to SharedPreferences
                            getSharedPreferences("openclaw", MODE_PRIVATE)
                                .edit().putString("deviceToken", token).apply()
                        }
                    }
                }

                // Notify CommandProcessor that we're connected
                CommandProcessor.getInstance(this).onConnected(this)
            } else {
                val error = msg.optJSONObject("error")
                val errorMsg = error?.optString("message", "Unknown error") ?: "Unknown error"
                appendLog("Connect failed: $errorMsg")
                updateStatus("Auth failed")

                // If not paired, try to pair
                if (errorMsg.contains("PAIRING") || errorMsg.contains("UNAUTHORIZED")) {
                    attemptPairing()
                }
            }
        }
    }

    private fun attemptPairing() {
        // Read bootstrap token from shared prefs
        val prefs = getSharedPreferences("openclaw", MODE_PRIVATE)
        val bootstrapToken = prefs.getString("bootstrapToken", "")
        if (bootstrapToken.isNullOrEmpty()) {
            appendLog("No bootstrap token available for pairing")
            return
        }

        appendLog("Attempting device pairing with bootstrap token...")

        // Reconnect with bootstrap token
        val ws = webSocket ?: return
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

        val pairReq = JSONObject().apply {
            put("type", "req")
            put("id", "pair-${requestIdCounter.incrementAndGet()}")
            put("method", "connect")
            put("params", JSONObject().apply {
                put("minProtocol", 3)
                put("maxProtocol", 3)
                put("client", JSONObject().apply {
                    put("id", "android-node")
                    put("version", "1.0.0")
                    put("platform", "android")
                    put("mode", "node")
                })
                put("role", "node")
                put("scopes", JSONArray())
                put("auth", JSONObject().apply {
                    put("token", bootstrapToken)
                })
                put("device", JSONObject().apply {
                    put("id", deviceId)
                })
            })
        }
        ws.send(pairReq.toString())
    }

    private fun handleInvoke(ws: WebSocket, msg: JSONObject) {
        val invokeId = msg.optString("id", msg.optString("invokeId", ""))
        val method = msg.optString("method", "")
        val params = msg.optJSONObject("params") ?: JSONObject()

        appendLog("Invoke: $method")

        // Forward to CommandProcessor
        CommandProcessor.getInstance(this).processCommand(method, params) { result ->
            sendInvokeResult(invokeId, result)
        }
    }

    private fun sendInvokeResult(invokeId: String, resultJson: String) {
        val ws = webSocket ?: return
        try {
            val payload = if (resultJson.trimStart().startsWith("{")) {
                JSONObject(resultJson)
            } else {
                JSONObject().apply { put("data", resultJson) }
            }
            val res = JSONObject().apply {
                put("type", "invoke-res")
                put("id", invokeId)
                put("ok", true)
                put("payload", payload)
            }
            ws.send(res.toString())
            appendLog("Sent result for invoke: $invokeId")
        } catch (e: Exception) {
            appendLog("Failed to send invoke result: ${e.message}")
        }
    }

    private fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connected = false
        deviceToken = null
        updateStatus("Disconnected")
        updateNotification("Disconnected")
        appendLog("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 5) {
            appendLog("Max reconnect attempts reached")
            return
        }
        val delay = minOf(1000L shl reconnectAttempts, maxReconnectDelay)
        reconnectAttempts++
        appendLog("Reconnecting in ${delay / 1000}s (attempt $reconnectAttempts)...")
        android.os.Handler(mainLooper).postDelayed({
            if (!connected && gatewayUrl.isNotEmpty()) {
                val prefs = getSharedPreferences("openclaw", MODE_PRIVATE)
                val token = prefs.getString("deviceToken", "") ?: ""
                connect(gatewayUrl, token)
            }
        }, delay)
    }

    fun getDeviceToken(): String? = deviceToken

    // --- Notification & UI Updates ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gateway Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OpenClaw Gateway connection status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw Node")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        statusCallback?.invoke(status)
    }

    private fun appendLog(line: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        currentLog = "$timestamp $line\n$currentLog"
        // Keep last 50 lines
        val lines = currentLog.split("\n")
        if (lines.size > 50) {
            currentLog = lines.take(50).joinToString("\n")
        }
        logCallback?.invoke(currentLog)
        Log.d(TAG, line)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
