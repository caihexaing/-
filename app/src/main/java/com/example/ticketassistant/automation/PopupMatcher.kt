package com.example.ticketassistant.automation

import android.view.accessibility.AccessibilityNodeInfo

/** Finds only a clearly identifiable, ordinary blocking popup close action. */
internal object PopupMatcher {
    sealed interface Result {
        data class Unique(val node: AccessibilityNodeInfo, val label: String) : Result
        data class Ambiguous(val count: Int) : Result
        object None : Result
    }

    fun find(root: AccessibilityNodeInfo): Result {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        walk(root) { node ->
            val label = nodeLabel(node)
            if (label == null || !node.isVisibleToUser) return@walk
            val clickable = clickableNode(node) ?: return@walk
            val container = popupContainer(node) ?: return@walk
            val popupText = textContent(container)
            if (!isOrdinaryPopupText(popupText) || containsSensitivePrompt(popupText)) return@walk
            candidates += clickable to label
        }
        val unique = candidates.distinctBy { System.identityHashCode(it.first) }
        return when {
            unique.size == 1 -> Result.Unique(unique.single().first, unique.single().second)
            unique.size > 1 -> Result.Ambiguous(unique.size)
            else -> Result.None
        }
    }

    internal fun isOrdinaryPopupText(text: String): Boolean {
        val normalized = normalize(text)
        return listOf("温馨提示", "系统提示", "提示").any(normalized::contains) &&
            listOf("关闭", "知道了", "暂不").any(normalized::contains)
    }

    internal fun containsSensitivePrompt(text: String): Boolean {
        val normalized = normalize(text)
        return listOf(
            "验证码", "滑块", "请登录", "登录失效", "登录后", "身份核验", "实名认证", "风控", "候补协议"
        ).any(normalized::contains)
    }

    private fun walk(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        visit(node)
        for (index in 0 until node.childCount) walk(node.getChild(index), visit)
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String? =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .firstOrNull(::isCloseLabel)

    private fun isCloseLabel(value: String): Boolean {
        val normalized = normalize(value)
        return normalized in setOf("关闭", "知道了", "暂不") || normalized.endsWith("关闭")
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (parent?.isClickable == true && parent.isVisibleToUser) return parent
            parent = parent?.parent
        }
        return null
    }

    private fun popupContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CONTAINER_DEPTH) {
            if (current != null && hasPopupSemantics(current)) return current
            current = current?.parent
        }
        return null
    }

    private fun hasPopupSemantics(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase().orEmpty()
        val resourceId = node.viewIdResourceName?.lowercase().orEmpty()
        return listOf("dialog", "popup", "alert", "modal").any {
            className.contains(it) || resourceId.contains(it)
        }
    }

    private fun textContent(root: AccessibilityNodeInfo): String = buildString {
        fun appendVisible(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
            if (node == null || (!includeRoot && !node.isVisibleToUser)) return
            node.text?.let { append(' ').append(it) }
            node.contentDescription?.let { append(' ').append(it) }
            for (index in 0 until node.childCount) appendVisible(node.getChild(index))
        }
        appendVisible(root, includeRoot = true)
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "").lowercase()

    private const val MAX_PARENT_DEPTH = 6
    private const val MAX_CONTAINER_DEPTH = 8
}
