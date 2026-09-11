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
        val input = findEditableField(root, field) ?: return InteractionResult.WAITING
        val currentValue = input.text?.toString().orEmpty()
        val candidates = findFieldScopedCandidates(root, input, field, stationName)
        if (candidates.size > 1) {
            record("${field.actionName()}候选站不唯一")
            return InteractionResult.FAILED
        }
        if (candidates.size == 1) {
            if (!clickNodeOrParent(candidates.single())) {
                record("${field.actionName()}候选站点击未派发")
                return InteractionResult.FAILED
            }
            record("已选择${field.actionName()}候选站，等待字段值确认")
            return InteractionResult.WAITING
        }
        if (exactStationCandidate(currentValue, stationName)) {
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
            val directValues = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.hintText?.toString(),
                node.viewIdResourceName
            )
            val directContext = fieldContextKind(directValues.joinToString(" "), field)
            if (directContext == FieldContext.OPPOSITE || directContext == FieldContext.AMBIGUOUS) {
                return@findNodes false
            }
            if (directContext == FieldContext.TARGET) return@findNodes true

            when (nearestFieldContext(node, field)) {
                FieldContext.TARGET -> true
                FieldContext.NONE -> fieldContextMatches(parentContext(node), field)
                FieldContext.OPPOSITE, FieldContext.AMBIGUOUS -> false
            }
        }
        return candidates.singleOrNull()
    }

    /**
     * Station suggestions are scoped to the active editable field. A matching
     * station elsewhere on the page is never a valid candidate for this field.
     */
    private fun findFieldScopedCandidates(
        root: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo,
        field: SearchField,
        stationName: String
    ): List<AccessibilityNodeInfo> {
        val fieldContainer = findFieldContainer(input, field)
        val matches = findNodes(root) { node ->
            if (node.isEditable) return@findNodes false
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { exactStationCandidate(it, stationName) }
        }
        val eligible = matches.mapNotNull { stationNode ->
            if (hasEditableAncestor(stationNode, input)) return@mapNotNull null
            val association = nearestFieldContext(stationNode, field)
            val inContainer = fieldContainer?.let { isDescendantOrSelf(stationNode, it) } == true
            when {
                association == FieldContext.OPPOSITE || association == FieldContext.AMBIGUOUS -> null
                association == FieldContext.TARGET && (fieldContainer == null || inContainer) -> clickableNode(stationNode)
                association == FieldContext.NONE && input.isFocused && matches.size == 1 -> clickableNode(stationNode)
                else -> null
            }
        }.distinctBy(::nodeIdentity)
        return eligible
    }

    private fun findFieldContainer(input: AccessibilityNodeInfo, field: SearchField): AccessibilityNodeInfo? {
        // Keep the editable node itself out of the scope. Suggestions are
        // normally siblings inside its labelled container, not descendants of
        // the EditText node.
        var current: AccessibilityNodeInfo? = input.parent
        repeat(MAX_PARENT_DEPTH) {
            if (current == null) return@repeat
            val localText = listOfNotNull(
                current.text?.toString(),
                current.contentDescription?.toString(),
                current.hintText?.toString(),
                current.viewIdResourceName
            ).joinToString(" ")
            when (fieldContextKind(localText, field)) {
                FieldContext.TARGET -> return current
                FieldContext.OPPOSITE, FieldContext.AMBIGUOUS -> return null
                FieldContext.NONE -> Unit
            }
            current = current.parent
        }
        return null
    }

    private fun isDescendantOrSelf(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_DEPTH + 1) {
            if (current == null) return@repeat
            if (current === ancestor || current == ancestor) return true
            current = current.parent
        }
        return false
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
        fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
            if (node == null || (!includeRoot && !node.isVisibleToUser)) return
            if (predicate(node)) result += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root, includeRoot = true)
        return result
    }

    private fun nearestFieldContext(node: AccessibilityNodeInfo, field: SearchField): FieldContext {
        var parent = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (parent == null) return@repeat
            val localText = listOfNotNull(
                parent.text?.toString(),
                parent.contentDescription?.toString(),
                parent.hintText?.toString(),
                parent.viewIdResourceName
            ).joinToString(" ")
            val context = fieldContextKind(localText, field)
            if (context != FieldContext.NONE) return context
            parent = parent.parent
        }
        return FieldContext.NONE
    }

    private fun fieldContextKind(value: String, field: SearchField): FieldContext {
        val normalized = value.trim().replace(Regex("\\s+"), "")
        if (normalized.isBlank()) return FieldContext.NONE
        val target = fieldLabelMatches(normalized, field) || resourceLabelMatches(normalized, field)
        val opposite = when (field) {
            SearchField.DEPARTURE -> SearchField.ARRIVAL
            SearchField.ARRIVAL -> SearchField.DEPARTURE
            SearchField.DATE -> null
        }?.let { other ->
            fieldLabelMatches(normalized, other) || resourceLabelMatches(normalized, other)
        } == true
        return when {
            target && opposite -> FieldContext.AMBIGUOUS
            target -> FieldContext.TARGET
            opposite -> FieldContext.OPPOSITE
            else -> FieldContext.NONE
        }
    }

    private fun hasEditableAncestor(node: AccessibilityNodeInfo, input: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (current == null) return@repeat
            if (current === input || current == input || current?.isEditable == true) return true
            current = current.parent
        }
        return false
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

    private enum class FieldContext { NONE, TARGET, OPPOSITE, AMBIGUOUS }
}
