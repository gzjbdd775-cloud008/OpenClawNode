package com.openclaw.node

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Processes incoming node commands from the Gateway.
 * Dispatches to appropriate handlers (AccessibilityService, ScreenCapture, etc.)
 */
class CommandProcessor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CmdProcessor"
        @Volatile
        private var instance: CommandProcessor? = null

        fun getInstance(context: Context): CommandProcessor {
            return instance ?: synchronized(this) {
                instance ?: CommandProcessor(context.applicationContext).also { instance = it }
            }
        }
    }

    // Callbacks to be set by MainActivity
    private var accessibilityService: NodeAccessibilityService? = null
    private var screenCaptureService: ScreenCaptureService? = null
    private var webSocketService: GatewayWebSocketService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setAccessibilityService(service: NodeAccessibilityService?) {
        accessibilityService = service
    }

    fun setScreenCaptureService(service: ScreenCaptureService?) {
        screenCaptureService = service
    }

    fun setWebSocketService(service: GatewayWebSocketService?) {
        webSocketService = service
    }

    fun onConnected(wsService: GatewayWebSocketService) {
        webSocketService = wsService
    }

    /**
     * Process a command from the gateway.
     * Calls resultCallback with JSON string result.
     */
    fun processCommand(
        method: String,
        params: JSONObject,
        resultCallback: (String) -> Unit
    ) {
        Log.d(TAG, "Processing command: $method")

        mainHandler.post {
            try {
                val result = executeCommand(method, params)
                resultCallback(result)
            } catch (e: Exception) {
                Log.e(TAG, "Command error: $method", e)
                resultCallback(JSONObject().apply {
                    put("error", e.message ?: "Unknown error")
                }.toString())
            }
        }
    }

    private fun executeCommand(method: String, params: JSONObject): String {
        return when (method) {
            "openApp" -> openApp(params)
            "click" -> click(params)
            "clickElement" -> clickElement(params)
            "swipe" -> swipe(params)
            "type" -> typeText(params)
            "back" -> performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            "screenshot" -> takeScreenshot()
            "getScreenText" -> getScreenText()
            "getScreenNodes" -> getScreenNodes()
            "deviceInfo" -> getDeviceInfo()
            "notificationList" -> getNotificationList()
            "health" -> getHealth()
            else -> JSONObject().apply {
                put("error", "Unknown command: $method")
            }.toString()
        }
    }

    private fun openApp(params: JSONObject): String {
        val packageName = params.optString("packageName", params.optString("app", ""))
        if (packageName.isEmpty()) {
            return JSONObject().apply { put("error", "packageName required") }.toString()
        }

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                return JSONObject().apply {
                    put("success", true)
                    put("message", "Opened $packageName")
                }.toString()
            } else {
                return JSONObject().apply {
                    put("error", "Package not found: $packageName")
                }.toString()
            }
        } catch (e: Exception) {
            return JSONObject().apply {
                put("error", e.message)
            }.toString()
        }
    }

    private fun click(params: JSONObject): String {
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        if (x < 0 || y < 0) {
            return JSONObject().apply { put("error", "x and y required") }.toString()
        }
        return accessibilityService?.performClick(x, y) ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun clickElement(params: JSONObject): String {
        val text = params.optString("text", "")
        val contentDesc = params.optString("contentDesc", "")
        if (text.isEmpty() && contentDesc.isEmpty()) {
            return JSONObject().apply { put("error", "text or contentDesc required") }.toString()
        }
        return accessibilityService?.clickElementByText(text, contentDesc) ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun swipe(params: JSONObject): String {
        val x1 = params.optInt("x1", params.optInt("fromX", -1))
        val y1 = params.optInt("y1", params.optInt("fromY", -1))
        val x2 = params.optInt("x2", params.optInt("toX", -1))
        val y2 = params.optInt("y2", params.optInt("toY", -1))
        val duration = params.optLong("duration", 300L)

        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return JSONObject().apply { put("error", "x1,y1,x2,y2 required") }.toString()
        }
        return accessibilityService?.performSwipe(x1, y1, x2, y2, duration) ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun typeText(params: JSONObject): String {
        val text = params.optString("text", "")
        if (text.isEmpty()) {
            return JSONObject().apply { put("error", "text required") }.toString()
        }
        return accessibilityService?.performTypeText(text) ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun performGlobalAction(action: Int): String {
        return accessibilityService?.let { service ->
            val success = service.performGlobalAction(action)
            JSONObject().apply { put("success", success) }
        }?.toString() ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun takeScreenshot(): String {
        return screenCaptureService?.let { service ->
            service.captureScreenshot()
        } ?: JSONObject().apply {
            put("error", "Screen capture service not active")
        }.toString()
    }

    private fun getScreenText(): String {
        return accessibilityService?.let { service ->
            service.getScreenTextContent()
        } ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun getScreenNodes(): String {
        return accessibilityService?.let { service ->
            service.getScreenNodeTree()
        } ?: JSONObject().apply {
            put("error", "Accessibility service not active")
        }.toString()
    }

    private fun getDeviceInfo(): String {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
            put("display", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
        }.toString()
    }

    private fun getNotificationList(): String {
        return try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val activeNotifications = notificationManager.activeNotifications
            val notifications = JSONArray()
            for (n in activeNotifications) {
                val extras = n.notification.extras
                val title = extras?.getString(android.app.Notification.EXTRA_TITLE) ?: ""
                val text = extras?.getString(android.app.Notification.EXTRA_TEXT) ?: ""
                notifications.put(JSONObject().apply {
                    put("packageName", n.packageName)
                    put("tag", n.tag)
                    put("id", n.id)
                    put("title", title)
                    put("text", text)
                    put("postTime", n.postTime)
                })
            }
            JSONObject().apply {
                put("notifications", notifications)
                put("count", notifications.length())
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply { put("error", e.message) }.toString()
        }
    }

    private fun getHealth(): String {
        return JSONObject().apply {
            put("status", "ok")
            put("accessibilityActive", accessibilityService != null)
            put("screenCaptureActive", screenCaptureService?.isCapturing() ?: false)
            put("websocketConnected", webSocketService?.let {
                GatewayWebSocketService.currentStatus.contains("Connected")
            } ?: false)
            put("deviceInfo", getDeviceInfo())
        }.toString()
    }
}
