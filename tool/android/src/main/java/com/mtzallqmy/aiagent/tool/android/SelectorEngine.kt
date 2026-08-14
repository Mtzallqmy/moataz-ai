package com.mtzallqmy.aiagent.tool.android

import kotlin.math.sqrt

/** Internal UI tree model converted from AccessibilityNodeInfo. */
data class UiNode(
    val packageName: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: UiBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val children: List<UiNode>,
)

data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Selector system: atomic selectors + combinators (AND/OR/NOT/PARENT/CHILD/DESCENDANT/NEAR).
 * Priority per requirements: 1) semantic selectors 2) coordinate fallback 3) vision fallback (external).
 */
sealed class Selector {
    data class ByText(val text: String) : Selector()
    data class ByTextContains(val fragment: String) : Selector()
    data class ByContentDescription(val desc: String) : Selector()
    data class ByResourceId(val id: String) : Selector()
    data class ByClass(val className: String) : Selector()
    data class ByBounds(val bounds: UiBounds) : Selector()
    data class ByPackage(val packageName: String) : Selector()
    data class ByRole(val role: String) : Selector()
    data class ByState(val enabled: Boolean? = null, val clickable: Boolean? = null) : Selector()
    data class And(val left: Selector, val right: Selector) : Selector()
    data class Or(val left: Selector, val right: Selector) : Selector()
    data class Not(val inner: Selector) : Selector()
    data class Child(val parent: Selector, val inner: Selector) : Selector()
    data class Descendant(val ancestor: Selector, val inner: Selector) : Selector()
    data class Near(val anchor: Selector, val distancePx: Int, val inner: Selector) : Selector()
}

object SelectorEngine {

    fun find(root: UiNode, selector: Selector): List<UiNode> {
        val all = root.flatten()
        return when (selector) {
            is Selector.ByText -> all.filter { it.text == selector.text }
            is Selector.ByTextContains -> all.filter { it.text.contains(selector.fragment, ignoreCase = true) }
            is Selector.ByContentDescription -> all.filter { it.contentDescription == selector.desc }
            is Selector.ByResourceId -> all.filter { it.resourceId == selector.id }
            is Selector.ByClass -> all.filter { it.className == selector.className }
            is Selector.ByBounds -> {
                val s = selector.bounds
                all.filter { n -> n.bounds.left >= s.left && n.bounds.top >= s.top && n.bounds.right <= s.right && n.bounds.bottom <= s.bottom }
            }
            is Selector.ByPackage -> all.filter { it.packageName == selector.packageName }
            is Selector.ByRole -> all.filter { matchesRole(it, selector.role) }
            is Selector.ByState -> all.filter { n ->
                (selector.enabled == null || n.enabled == selector.enabled) &&
                        (selector.clickable == null || n.clickable == selector.clickable)
            }
            is Selector.And -> find(root, selector.left).intersect(find(root, selector.right)).toList()
            is Selector.Or -> (find(root, selector.left) + find(root, selector.right)).distinct()
            is Selector.Not -> all - find(root, selector.inner).toSet()
            is Selector.Child -> find(root, selector.parent).flatMap { it.children }.filter { matches(it, selector.inner) }
            is Selector.Descendant -> find(root, selector.ancestor).flatMap { it.flatten() }.filter { matches(it, selector.inner) }.distinct()
            is Selector.Near -> {
                val anchors = find(root, selector.anchor)
                if (anchors.isEmpty()) emptyList()
                else find(root, selector.inner).filter { target ->
                    anchors.any { a ->
                        val dx = (a.bounds.centerX - target.bounds.centerX).toDouble()
                        val dy = (a.bounds.centerY - target.bounds.centerY).toDouble()
                        sqrt(dx * dx + dy * dy) <= selector.distancePx
                    }
                }
            }
        }
    }

    fun matches(node: UiNode, selector: Selector): Boolean = find(node, selector).contains(node)

    /** Coordinate fallback: innermost node covering the point. */
    fun atCoordinates(root: UiNode, x: Int, y: Int): UiNode? {
        val covering = root.flatten().filter { n ->
            x in n.bounds.left..n.bounds.right && y in n.bounds.top..n.bounds.bottom
        }
        return covering.minByOrNull { (it.bounds.right - it.bounds.left) * (it.bounds.bottom - it.bounds.top) }
    }

    private fun matchesRole(node: UiNode, role: String): Boolean = when (role.lowercase()) {
        "button" -> node.clickable && (node.className.contains("Button", ignoreCase = true))
        "textfield", "textbox", "input" -> node.editable
        "link" -> node.className.contains("TextView", ignoreCase = true) && node.text.isNotBlank()
        "checkbox" -> node.className.contains("CheckBox", ignoreCase = true)
        "switch" -> node.className.contains("Switch", ignoreCase = true)
        "list" -> node.scrollable
        "text" -> node.text.isNotBlank()
        else -> false
    }

    private fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * Verification loop: every important device action must verify the expected
 * post-state; a successful tap() never counts as task success by itself.
 */
class VerificationLoop(
    private val treeProvider: () -> UiNode?,
    private val maxAttempts: Int = 3,
    private val delayMs: Long = 800,
) {
    /**
     * Observe → find target → execute → observe again → verify expected state.
     */
    suspend fun verifyAfter(
        action: suspend () -> Unit,
        expectedState: (UiNode) -> Boolean,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): Boolean {
        action()
        repeat(maxAttempts) {
            sleep(delayMs)
            val after = treeProvider()
            if (after != null && expectedState(after)) return true
        }
        return false
    }
}
