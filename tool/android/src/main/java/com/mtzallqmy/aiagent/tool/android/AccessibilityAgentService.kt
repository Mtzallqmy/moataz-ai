package com.mtzallqmy.aiagent.tool.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * Real Accessibility Service for device-agent observation and actions.
 * Extracts package, window, class, text, contentDescription, resourceId,
 * bounds, clickable/editable/focusable/scrollable/enabled/checked/selected.
 */
class AccessibilityAgentService : AccessibilityService() {
    companion object {
        @Volatile
        private var currentInstance: AccessibilityAgentService? = null
        /** Static accessor for backend discovery and health checks. */
        fun current(): AccessibilityAgentService? = currentInstance

        private const val MAX_TREE_DEPTH = 64
        private const val MAX_TREE_NODES = 5_000
        private const val MAX_NODE_TEXT_CHARS = 4_096
        private const val MAX_RESOURCE_ID_CHARS = 1_024
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance === this) currentInstance = null
    }

    /** Latest observed tree snapshot, updated on accessibility events (versioned). */
    @Volatile
    private var latestSnapshot: Snapshot = Snapshot(0L, null)
    private val snapshotSequence = AtomicLong(0L)

    /** Versioned snapshot so callers can detect stale state after actions. */
    data class Snapshot(val version: Long, val root: UiNode?)

    fun currentTreeRoot(): AccessibilityNodeInfo? = rootInActiveWindow
    fun latestSnapshot(): Snapshot = latestSnapshot

    /** Convert the live window tree into a bounded, detached internal node model. */
    fun captureTree(): Snapshot {
        val budget = ParseBudget(MAX_TREE_NODES)
        val root = rootInActiveWindow?.let { parseNode(it, depth = 0, budget = budget) }
        val next = Snapshot(snapshotSequence.incrementAndGet(), root)
        latestSnapshot = next
        return next
    }

    private class ParseBudget(var remaining: Int)

    private fun parseNode(node: AccessibilityNodeInfo, depth: Int, budget: ParseBudget): UiNode? {
        if (budget.remaining <= 0) return null
        budget.remaining -= 1

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val children = if (depth >= MAX_TREE_DEPTH) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until node.childCount) {
                    if (budget.remaining <= 0) break
                    val child = node.getChild(index) ?: continue
                    try {
                        parseNode(child, depth + 1, budget)?.let(::add)
                    } finally {
                        @Suppress("DEPRECATION")
                        child.recycle()
                    }
                }
            }
        }
        return UiNode(
            packageName = node.packageName?.toString() ?: "",
            className = node.className?.toString() ?: "",
            text = node.text?.toString()?.take(MAX_NODE_TEXT_CHARS) ?: "",
            contentDescription = node.contentDescription?.toString()?.take(MAX_NODE_TEXT_CHARS) ?: "",
            resourceId = node.viewIdResourceName?.take(MAX_RESOURCE_ID_CHARS) ?: "",
            bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            focusable = node.isFocusable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            checked = node.isChecked,
            selected = node.isSelected,
            children = children,
        )
    }

    // ---------- Actions ----------

    /** Tap at coordinates (falls back to AccessibilityNodeInfo.performAction when a node matches). */
    fun tapAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun longPressAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1500))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    /** Click a resolved node. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    /** Type text into an editable node. */
    fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val bundle = android.os.Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        }
        return false
    }

    fun clearText(node: AccessibilityNodeInfo): Boolean {
        val bundle = android.os.Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        }
        return false
    }

    fun scrollUp(): Boolean = currentTreeRoot()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true

    fun scrollDown(): Boolean = currentTreeRoot()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    fun swipe(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x0.toFloat(), y0.toFloat()); lineTo(x1.toFloat(), y1.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openApp(packageName: String): Boolean {
        return runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Keep a fresh bounded snapshot available so actions can observe state
        // before and after an operation without recursively walking unbounded UI trees.
        runCatching { captureTree() }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        info.feedbackType = (AccessibilityServiceInfo.FEEDBACK_GENERIC)
        info.flags = (AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
        setServiceInfo(info)
    }
}
