package com.example.ticketassistant.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Performs only uniquely identifiable actions on the official ticket-search UI. */
class OfficialSearchInteractor(
    private val record: (String) -> Unit = {}
) {
    fun openTickets(root: AccessibilityNodeInfo): InteractionResult {
        val action = findActionNode(root, SearchAction.OPEN_TICKETS) ?: return InteractionResult.WAITING
        return click(action, "打开车票查询入口")
    }

    fun fillStation(
        root: AccessibilityNodeInfo,
        field: SearchField,
        stationName: String
    ): InteractionResult {
        val candidate = findExactCandidateNode(root, stationName)
        if (candidate != null) return click(candidate, "选择${field.actionName()}候选站")

        val input = findEditableField(root, field) ?: return InteractionResult.WAITING
        if (exactStationCandidate(input.text?.toString().orEmpty(), stationName)) {
            record("${field.actionName()}已是目标站")
            return InteractionResult.DONE
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, stationName)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            record("${field.actionName()}输入框无法聚焦")
            return InteractionResult.FAILED
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            record("${field.actionName()}输入动作未派发")
            return InteractionResult.FAILED
        }
        record("已填写${field.actionName()}，等待候选站")
        return InteractionResult.WAITING
    }

    fun fillDate(root: AccessibilityNodeInfo, date: String): InteractionResult {
        val input = findEditableField(root, SearchField.DATE)
        if (input != null) {
            if (matchesTravelDate(input.text?.toString().orEmpty(), date)) {
                record("乘车日期已是目标日期")
                return InteractionResult.DONE
            }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, date)
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                record("日期输入框无法聚焦")
                return InteractionResult.FAILED
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                record("日期输入动作未派发")
                return InteractionResult.FAILED
            }
            record("已填写乘车日期，等待页面更新")
            return InteractionResult.WAITING
        }

        val exactDateNodes = findNodes(root) { node ->
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            value.any { text -> dateVariants(date).any { normalizeDateText(it) == normalizeDateText(text) } }
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        if (exactDateNodes.size == 1) return click(exactDateNodes.single(), "选择目标日期")
        if (exactDateNodes.size > 1) {
            record("目标日期控件不唯一")
            return InteractionResult.FAILED
        }

        val controls = findNodes(root) { node ->
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString())
                .any { fieldLabelMatches(it, SearchField.DATE) }
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        if (controls.size == 1) return click(controls.single(), "打开日期选择器")
        if (controls.size > 1) {
            record("日期控件不唯一")
            return InteractionResult.FAILED
        }
        return InteractionResult.WAITING
    }

    fun submitSearch(root: AccessibilityNodeInfo): InteractionResult {
        val action = findActionNode(root, SearchAction.SUBMIT_SEARCH) ?: return InteractionResult.WAITING
        return click(action, "查询车次")
    }

    private fun click(node: AccessibilityNodeInfo, action: String): InteractionResult {
        if (clickNodeOrParent(node)) {
            record(action)
            return InteractionResult.DONE
        }
        record(action + "动作未派发")
        return InteractionResult.FAILED
    }

    private fun findEditableField(root: AccessibilityNodeInfo, field: SearchField): AccessibilityNodeInfo? {
        val candidates = findNodes(root) { node ->
            if (!node.isEditable) return@findNodes false
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.hintText?.toString(),
                node.viewIdResourceName,
                parentContext(node)
            )
            values.any { fieldLabelMatches(it, field) || resourceLabelMatches(it, field) }
        }
        return candidates.singleOrNull()
    }

    private fun findExactCandidateNode(root: AccessibilityNodeInfo, stationName: String): AccessibilityNodeInfo? {
        val candidates = findNodes(root) { node ->
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { exactStationCandidate(it, stationName) }
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        return candidates.singleOrNull()
    }

    private fun findActionNode(root: AccessibilityNodeInfo, action: SearchAction): AccessibilityNodeInfo? {
        val candidates = findNodes(root) { node ->
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { actionLabelMatches(it, action) }
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        return when {
            candidates.size == 1 -> candidates.single()
            else -> null
        }
    }

    private fun findNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (predicate(node)) result += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return result
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (parent?.isClickable == true) return parent
            parent = parent?.parent
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (parent == null) return false
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        return false
    }

    private fun parentContext(node: AccessibilityNodeInfo): String = buildString {
        var parent = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (parent == null) return@repeat
            parent.text?.let { append(' ').append(it) }
            parent.contentDescription?.let { append(' ').append(it) }
            parent.hintText?.let { append(' ').append(it) }
            parent.viewIdResourceName?.let { append(' ').append(it) }
            parent = parent.parent
        }
    }

    private fun nodeIdentity(node: AccessibilityNodeInfo): Int = System.identityHashCode(node)

    private fun SearchField.actionName(): String = when (this) {
        SearchField.DEPARTURE -> "出发站"
        SearchField.ARRIVAL -> "到达站"
        SearchField.DATE -> "乘车日期"
    }

    private fun normalizeDateText(value: String): String = value.trim().replace(Regex("\\s+"), "")

    companion object {
        private const val MAX_PARENT_DEPTH = 5
    }
}
