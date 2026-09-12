package com.example.ticketassistant.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Performs only uniquely identifiable actions on the official ticket-search UI. */
class OfficialSearchInteractor(
    private val record: (String) -> Unit = {}
) {
    private var stationField: SearchField? = null
    private var stationTarget: String? = null
    private var stationPhase = StationSelectionPhase.IDLE
    private var stationPickerSeen = false
    private var stationCandidateSeen = false
    private var stationBeforeClickFingerprint: String? = null

    fun openTickets(root: AccessibilityNodeInfo): InteractionResult {
        val action = findActionNode(root, SearchAction.OPEN_TICKETS) ?: return InteractionResult.WAITING
        return click(action, "打开车票查询入口")
    }

    fun fillStation(
        root: AccessibilityNodeInfo,
        field: SearchField,
        stationName: String
    ): InteractionResult {
        prepareStationFlow(field, stationName)
        val input = findEditableField(root, field)
        val currentValue = input?.text?.toString().orEmpty()
        val pickerVisible = isStationPickerVisible(root)
        val visibleCandidates = findFieldScopedCandidates(root, input, field, stationName, pickerVisible)
        val currentFingerprint = stationSnapshotFingerprint(root)
        if (pickerVisible) stationPickerSeen = true
        if (visibleCandidates.isNotEmpty()) stationCandidateSeen = true
        val candidates = visibleCandidates

        // ACTION_SET_TEXT only changes the query. It is not proof that the
        // picker row was selected, so wait for a post-click page refresh.
        if (stationPhase == StationSelectionPhase.WAITING_CONFIRMATION) {
            val snapshotChanged = stationBeforeClickFingerprint?.let { it != currentFingerprint } == true
            if (pickerVisible || candidates.isNotEmpty()) {
                record("等待${field.actionName()}候选站点击后的页面刷新（候选数=${candidates.size}，快照变化=${snapshotChanged}）")
                return InteractionResult.WAITING
            }
            if (stationSelectionConfirmed(
                    stationPhase,
                    currentValue,
                    stationName,
                    pickerVisible,
                    candidates.size,
                    stationPickerSeen || stationCandidateSeen,
                    snapshotChanged
                ) ||
                (snapshotChanged && (stationPickerSeen || stationCandidateSeen) &&
                    confirmedStationDisplay(root, input, field, stationName))
            ) {
                completeStationFlow()
                record("${field.actionName()}候选站已确认")
                return InteractionResult.DONE
            }
            record("${field.actionName()}候选站已点击，等待字段值确认")
            return InteractionResult.WAITING
        }

        // A pre-filled value is acceptable only when there is no active
        // picker. Values entered by the previous ACTION_SET_TEXT path are
        // handled by WAITING_CANDIDATE and cannot arrive here prematurely.
        if (stationPhase == StationSelectionPhase.IDLE &&
            !pickerVisible &&
            candidates.isEmpty() &&
            stationCandidateMatches(currentValue, stationName)
        ) {
            completeStationFlow()
            record("${field.actionName()}已是目标站")
            return InteractionResult.DONE
        }

        if (candidates.size > 1) {
            record("${field.actionName()}候选站不唯一（候选数=${candidates.size}）")
            return InteractionResult.FAILED
        }
        if (candidates.size == 1) {
            if (!clickNodeOrParent(candidates.single())) {
                record("${field.actionName()}候选站点击未派发")
                return InteractionResult.FAILED
            }
            stationPhase = StationSelectionPhase.WAITING_CONFIRMATION
            stationBeforeClickFingerprint = currentFingerprint
            record("已选择${field.actionName()}候选站，等待字段值确认")
            return InteractionResult.WAITING
        }

        if (stationPhase == StationSelectionPhase.WAITING_CANDIDATE) {
            record("等待${field.actionName()}候选站（候选数=0）")
            return InteractionResult.WAITING
        }

        if (input != null) {
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
            stationPhase = StationSelectionPhase.WAITING_CANDIDATE
            record("已填写${field.actionName()}，等待候选站")
            return InteractionResult.WAITING
        }

        val fieldControl = findStationFieldControl(root, field)
        if (fieldControl == null) {
            record("未找到${field.actionName()}输入或选择控件")
            return InteractionResult.WAITING
        }
        if (!clickNodeOrParent(fieldControl)) {
            record("${field.actionName()}选择控件点击未派发")
            return InteractionResult.FAILED
        }
        stationPhase = StationSelectionPhase.WAITING_CANDIDATE
        record("已打开${field.actionName()}选择控件，等待站点列表")
        return InteractionResult.WAITING
    }

    private fun prepareStationFlow(field: SearchField, target: String) {
        if (stationField == field && stationTarget == target) return
        stationField = field
        stationTarget = target
        stationPhase = StationSelectionPhase.IDLE
        stationPickerSeen = false
        stationCandidateSeen = false
        stationBeforeClickFingerprint = null
    }

    private fun completeStationFlow() {
        stationField = null
        stationTarget = null
        stationPhase = StationSelectionPhase.IDLE
        stationPickerSeen = false
        stationCandidateSeen = false
        stationBeforeClickFingerprint = null
    }

    private fun isStationPickerVisible(root: AccessibilityNodeInfo): Boolean {
        val text = rootText(root).replace(Regex("\\s+"), "")
        return listOf("选择出发站", "选择到达站", "站点列表", "热门站点", "车站选择", "常用站点")
            .any(text::contains)
    }

    private fun confirmedStationDisplay(
        root: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo?,
        field: SearchField,
        target: String
    ): Boolean {
        val container = input?.let { findFieldContainer(it, field) }
        return findNodes(root) { node ->
            if (!node.isVisibleToUser || node.isEditable) return@findNodes false
            if (!nodeValues(node).any { stationCandidateMatches(it, target) }) return@findNodes false
            val inContainer = container?.let { isDescendantOrSelf(node, it) } == true
            inContainer || nearestFieldContext(node, field) == FieldContext.TARGET
        }.isNotEmpty()
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
        input: AccessibilityNodeInfo?,
        field: SearchField,
        stationName: String,
        pickerVisible: Boolean
    ): List<AccessibilityNodeInfo> {
        if (!pickerVisible) return emptyList()
        val fieldContainer = input?.let { findFieldContainer(it, field) }
        val matches = findStationCandidateNodes(root) { node ->
            !node.isEditable && listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { stationCandidateMatches(it, stationName) }
        }
        val eligible = matches.mapNotNull { stationNode ->
            if (hasEditableAncestor(stationNode, input)) return@mapNotNull null
            val clickable = clickableNode(stationNode)
            // The form's selected station control can be a clickable parent
            // of the EditText and may contain the same text as the query.
            // It is not a picker row and must never be clicked again.
            if (input != null &&
                (isDescendantOrSelf(input, stationNode) ||
                    clickable?.let { isDescendantOrSelf(input, it) } == true)
            ) return@mapNotNull null
            val association = nearestFieldContext(stationNode, field)
            val inContainer = fieldContainer?.let { isDescendantOrSelf(stationNode, it) } == true
            when {
                association == FieldContext.OPPOSITE || association == FieldContext.AMBIGUOUS -> null
                association == FieldContext.TARGET && (fieldContainer == null || inContainer) -> clickable
                association == FieldContext.NONE && stationField == field && matches.size == 1 -> clickable
                else -> null
            }
        }.distinctBy(::nodeIdentity)
        return eligible
    }

    /**
     * Station suggestion rows in some 12306 builds are reported as a hidden
     * text node whose clickable parent is visible. Keep those rows available
     * for the scoped matcher; generic buttons and hidden page text remain
     * excluded from the normal action search.
     */
    private fun findStationCandidateNodes(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val visibleOrInteractive = node.isVisibleToUser || node.isClickable || clickableNode(node) != null
            if (visibleOrInteractive && predicate(node)) result += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return result
    }

    private fun findStationFieldControl(root: AccessibilityNodeInfo, field: SearchField): AccessibilityNodeInfo? {
        val controls = findNodes(root) { node ->
            val values = nodeValues(node)
            values.any { value -> fieldContextMatches(value, field) || resourceLabelMatches(value, field) }
        }.mapNotNull { node ->
            val control = clickableNode(node) ?: return@mapNotNull null
            if (fieldContextKind(nodeValues(control).joinToString(" "), field) == FieldContext.AMBIGUOUS) {
                return@mapNotNull null
            }
            control
        }.distinctBy(::nodeIdentity)
        return controls.singleOrNull()
    }

    private fun findFieldContainer(input: AccessibilityNodeInfo, field: SearchField): AccessibilityNodeInfo? {
        // Keep the editable node itself out of the scope. Suggestions are
        // normally siblings inside its labelled container, not descendants of
        // the EditText node.
        var current: AccessibilityNodeInfo? = input.parent
        repeat(MAX_PARENT_DEPTH) {
            if (current == null) return@repeat
            val localText = nodeValues(current).joinToString(" ")
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
        data class Candidate(val control: AccessibilityNodeInfo, val labels: List<String>)
        val candidates = findNodes(root) { node ->
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { actionLabelMatches(it, action) }
        }.mapNotNull { node ->
            clickableNode(node)?.let { Candidate(it, listOfNotNull(node.text?.toString(), node.contentDescription?.toString())) }
        }.distinctBy { nodeIdentity(it.control) }
        if (candidates.isEmpty()) return null

        // Prefer the dedicated ticket-search control over a bottom navigation
        // item whose label is only "车票". The action still must be unique.
        val preferred = when (action) {
            SearchAction.OPEN_TICKETS -> candidates.filter { candidate ->
                candidate.labels.any { value ->
                    val normalized = normalizeText(value)
                    normalized.contains("查询车票") || normalized.contains("余票查询") || normalized == "火车票"
                }
            }
            SearchAction.SUBMIT_SEARCH -> candidates.filter { candidate ->
                candidate.labels.any { value -> normalizeText(value) == "查询" || normalizeText(value) == "搜索" }
            }
            else -> emptyList()
        }
        return when {
            preferred.size == 1 -> preferred.single().control
            preferred.isNotEmpty() -> null
            candidates.size == 1 -> candidates.single().control
            else -> null
        }
    }

    private fun findNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
            if (node == null) return
            // Some 12306 builds expose the EditText as not visible while its
            // labelled container is visible. Keep editable nodes searchable,
            // but never admit hidden non-editable candidates or actions.
            val searchable = includeRoot || node.isVisibleToUser || node.isEditable
            if (searchable && predicate(node)) result += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root, includeRoot = true)
        return result
    }

    private fun nearestFieldContext(node: AccessibilityNodeInfo, field: SearchField): FieldContext {
        var parent = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (parent == null) return@repeat
            val localText = nodeValues(parent).joinToString(" ")
            val context = fieldContextKind(localText, field)
            if (context != FieldContext.NONE) return context
            parent = parent.parent
        }
        return FieldContext.NONE
    }

    private fun fieldContextKind(value: String, field: SearchField): FieldContext {
        val normalized = value.trim().replace(Regex("\\s+"), "")
        if (normalized.isBlank()) return FieldContext.NONE
        val target = fieldTargetLabelMatches(normalized, field) || resourceLabelMatches(normalized, field)
        val opposite = when (field) {
            SearchField.DEPARTURE -> SearchField.ARRIVAL
            SearchField.ARRIVAL -> SearchField.DEPARTURE
            SearchField.DATE -> null
        }?.let { other ->
            fieldTargetLabelMatches(normalized, other) || resourceLabelMatches(normalized, other)
        } == true
        return when {
            target && opposite -> FieldContext.AMBIGUOUS
            target -> FieldContext.TARGET
            opposite -> FieldContext.OPPOSITE
            else -> FieldContext.NONE
        }
    }

    private fun hasEditableAncestor(node: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(MAX_PARENT_DEPTH) {
            if (current == null) return@repeat
            if ((input != null && (current === input || current == input)) || current?.isEditable == true) return true
            current = current.parent
        }
        return false
    }

    private fun nodeValues(node: AccessibilityNodeInfo): List<String> = listOfNotNull(
        node.text?.toString(),
        node.contentDescription?.toString(),
        node.hintText?.toString(),
        node.viewIdResourceName
    )

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

    private fun rootText(root: AccessibilityNodeInfo): String = buildString {
        fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
            if (node == null) return
            if (!includeRoot && !node.isVisibleToUser) return
            node.text?.let { append(' ').append(it) }
            node.contentDescription?.let { append(' ').append(it) }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root, includeRoot = true)
    }

    private fun stationSnapshotFingerprint(root: AccessibilityNodeInfo): String {
        val snapshot = buildString {
            fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
                if (node == null || (!includeRoot && !node.isVisibleToUser)) return
                append(node.className).append('|')
                append(node.viewIdResourceName).append('|')
                append(node.text).append('|')
                append(node.contentDescription).append('|')
                append(node.hintText).append('|')
                append(node.isClickable).append('|').append(node.isEditable).append(';')
                for (index in 0 until node.childCount) walk(node.getChild(index))
            }
            walk(root, includeRoot = true)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(snapshot.toString().replace(Regex("\\s+"), "").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
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
