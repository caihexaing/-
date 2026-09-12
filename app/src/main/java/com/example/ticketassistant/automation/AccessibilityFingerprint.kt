package com.example.ticketassistant.automation

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Short-lived page-change guard. It is deliberately non-cryptographic: this
 * value only detects a changed accessibility tree between two UI actions.
 */
internal fun lightweightAccessibilityFingerprint(root: AccessibilityNodeInfo): String {
    var hash = 17L

    fun mix(value: Any?) {
        val text = value?.toString().orEmpty()
        hash = hash * 31 + text.length
        text.forEach { hash = hash * 31 + it.code }
    }

    fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
        if (node == null || (!includeRoot && !node.isVisibleToUser)) return
        mix(node.text)
        mix(node.contentDescription)
        mix(node.hintText)
        mix(node.childCount)
        mix(node.isClickable)
        mix(node.isEditable)
        for (index in 0 until node.childCount) walk(node.getChild(index))
    }

    walk(root, includeRoot = true)
    return java.lang.Long.toUnsignedString(hash, 16).take(16)
}
