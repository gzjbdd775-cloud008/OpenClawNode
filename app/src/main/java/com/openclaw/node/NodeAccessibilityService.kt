package com.openclaw.node

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility Service that monitors the screen and provides:
 * - Screen content reading
 * - Click/swipe/type operations
 * - Global actions (back, home, recents)
 * - Screen node tree extraction
 */
class NodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NodeAccessibility"
        private var instance: NodeAccessibilityService? = null

        fun getInstance(): NodeAccessibilityService? = instance
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility service created")

        // Register with CommandProcessor
        CommandProcessor.getInstance(this).setAccessibilityService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We process commands on demand; events are informational
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window changed: ${event.packageName} / ${event.className}")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content changed, could be used for auto-refresh
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // --- Public API (called from CommandProcessor) ---

    /**
     * Perform a click at specific screen coordinates.
     */
    fun performClick(x: Int, y: Int): String {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat(), y.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            val result = dispatchGesture(gesture, null, null)

            org.json.JSONObject().apply {
                put("success", result)
                put("x", x)
                put("y", y)
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }.toString()
        }
    }

    /**
     * Click on an element by its text or content description.
     */
    fun clickElementByText(text: String, contentDesc: String): String {
        return try {
            val root = rootInActiveWindow ?: return org.json.JSONObject().apply {
                put("error", "No active window")
            }.toString()

            val found = findAndClickNode(root, text, contentDesc)

            if (found) {
                org.json.JSONObject().apply {
                    put("success", true)
                    put("text", text)
                }.toString()
            } else {
                // Try a broader search on all windows
                var foundBroad = false
                windows?.forEach { windowInfo ->
                    val windowRoot = windowInfo.root ?: return@forEach
                    if (findAndClickNode(windowRoot, text, contentDesc)) {
                        foundBroad = true
                        return@forEach
                    }
                }

                org.json.JSONObject().apply {
                    put("success", foundBroad)
                    if (!foundBroad) put("error", "Element not found: $text")
                }.toString()
            }
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }.toString()
        }
    }

    /**
     * Perform a swipe gesture.
     */
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        return try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val result = dispatchGesture(gesture, null, null)

            org.json.JSONObject().apply {
                put("success", result)
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }.toString()
        }
    }

    /**
     * Type text into the currently focused element via clipboard + paste, or per-character actions.
     */
    fun performTypeText(text: String): String {
        return try {
            // Try clipboard paste approach first
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)

            // Use paste action if available
            val root = rootInActiveWindow
            if (root != null) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.isEditable) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    return org.json.JSONObject().apply {
                        put("success", true)
                        put("method", "paste")
                    }.toString()
                }
            }

            // Fallback: type per character via ACTION_SET_TEXT (limited)
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return org.json.JSONObject().apply {
                    put("success", true)
                    put("method", "setText")
                }.toString()
            }

            org.json.JSONObject().apply {
                put("success", false)
                put("error", "No editable field focused")
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }.toString()
        }
    }

    /**
     * Get screen text content as structured JSON.
     */
    fun getScreenTextContent(): String {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                return org.json.JSONObject().apply {
                    put("error", "No active window")
                }.toString()
            }

            val nodes = JSONArray()
            extractTextNodes(root, nodes)

            org.json.JSONObject().apply {
                val textBuilder = StringBuilder()
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i)
                    if (node != null) {
                        if (textBuilder.isNotEmpty()) textBuilder.append('\n')
                        textBuilder.append(node.optString("text", ""))
                    }
                }
                put("textContent", textBuilder.toString())
                put("nodes", nodes)
                put("nodeCount", nodes.length())
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("error", e.message)
            }.toString()
        }
    }

    /**
     * Get full accessibility node tree as JSON.
     */
    fun getScreenNodeTree(): String {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                return org.json.JSONObject().apply {
                    put("error", "No active window")
                }.toString()
            }

            val tree = nodeToJson(root)
            org.json.JSONObject().apply {
                put("tree", tree)
                put("packageName", root.packageName ?: "")
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("error", e.message)
            }.toString()
        }
    }

    // --- Private helpers ---

    private fun findAndClickNode(root: AccessibilityNodeInfo, text: String, contentDesc: String): Boolean {
        if (text.isNotEmpty()) {
            if (root.text?.toString()?.contains(text, ignoreCase = true) == true ||
                root.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
            ) {
                if (root.isClickable) {
                    root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                // Find clickable parent
                var parent = root.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
                // Click anyway
                root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        if (contentDesc.isNotEmpty()) {
            if (root.contentDescription?.toString()?.contains(contentDesc, ignoreCase = true) == true ||
                root.text?.toString()?.contains(contentDesc, ignoreCase = true) == true
            ) {
                if (root.isClickable) {
                    root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                val parent = root.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        // Recurse children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (findAndClickNode(child, text, contentDesc)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    private fun extractTextNodes(node: AccessibilityNodeInfo, result: JSONArray, depth: Int = 0) {
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() || !contentDesc.isNullOrEmpty()) {
            val entry = org.json.JSONObject().apply {
                put("text", text ?: "")
                put("contentDescription", contentDesc ?: "")
                put("className", node.className?.toString() ?: "")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("checked", node.isChecked)
                put("selected", node.isSelected)
                put("enabled", node.isEnabled)
                put("focused", node.isFocused)
                put("packageName", node.packageName ?: "")
                put("depth", depth)

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                put("bounds", "$bounds")
                put("x", bounds.centerX())
                put("y", bounds.centerY())
                put("width", bounds.width())
                put("height", bounds.height())
            }
            result.put(entry)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTextNodes(child, result, depth + 1)
            child.recycle()
        }
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            children.put(nodeToJson(child))
            child.recycle()
        }

        return org.json.JSONObject().apply {
            put("className", node.className?.toString() ?: "")
            put("text", node.text?.toString() ?: "")
            put("contentDescription", node.contentDescription?.toString() ?: "")
            put("viewId", node.viewIdResourceName ?: "")
            put("packageName", node.packageName ?: "")
            put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("focusable", node.isFocusable)
            put("focused", node.isFocused)
            put("selected", node.isSelected)
            put("checked", node.isChecked)
            put("enabled", node.isEnabled)
            put("password", node.isPassword)
            put("visibleToUser", node.isVisibleToUser)
            put("childCount", children.length())
            put("children", children)
        }
    }
}
