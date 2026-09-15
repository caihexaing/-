package com.example.ticketassistant.automation

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Performs only uniquely identifiable actions on the official ticket-search UI. */
class OfficialSearchInteractor(
    private val record: (String) -> Unit = {},
    private val allowInput: () -> Boolean = { true }
) {
    private var stationField: SearchField? = null
    private var stationTarget: String? = null
    private var stationPhase = StationSelectionPhase.IDLE
    private var stationPickerSeen = false
    private var stationCandidateSeen = false
    private var stationBeforeClickFingerprint: String? = null
    private var stationActionSent = false
    private var stationQueryWriteSent = false
    private var stationQueryConfirmed = false
    private var stationQueryInputKey: String? = null
    private var stationQueryWriteInputKey: String? = null
    private var stationInputRequiresFreshWrite = false
    private var stationQueryBeforeWriteFingerprint: String? = null
    private var stationCandidateScope = "NONE"
    private var stationCandidateCount = 0
    private var dateTarget: String? = null
    private var datePhase = DateSelectionPhase.IDLE
    private var dateBeforeClickFingerprint: String? = null
    private var datePickerWaitEvents = 0
    private var datePickerOpenRetries = 0
    private var datePickerLastFingerprint: String? = null

    fun reset() {
        stationField = null
        stationTarget = null
        stationPhase = StationSelectionPhase.IDLE
        stationPickerSeen = false
        stationCandidateSeen = false
        stationBeforeClickFingerprint = null
        stationActionSent = false
        stationQueryWriteSent = false
        stationQueryConfirmed = false
        stationQueryInputKey = null
        stationQueryWriteInputKey = null
        stationInputRequiresFreshWrite = false
        stationQueryBeforeWriteFingerprint = null
        stationCandidateScope = "NONE"
        stationCandidateCount = 0
        completeDateFlow()
    }

    internal fun stationDiagnostics(): StationFlowDiagnostics = StationFlowDiagnostics(
        phase = stationPhase,
        inputKey = stationQueryInputKey,
        queryWriteSent = stationQueryWriteSent,
        queryConfirmed = stationQueryConfirmed,
        candidateScope = stationCandidateScope,
        candidateCount = stationCandidateCount
    )

    fun openTickets(root: AccessibilityNodeInfo): InteractionResult {
        val action = findActionNode(root, SearchAction.OPEN_TICKETS) ?: return InteractionResult.WAITING
        return click(action, "打开车票查询入口")
    }

    /**
     * Validates the two station displays on the current search form. The
     * evidence is tied to each labelled control; arbitrary occurrences in
     * history cards, advertisements, or the opposite field are ignored.
     */
    fun checkSearchFormRoute(
        root: AccessibilityNodeInfo,
        from: String,
        to: String
    ): SearchContextCheck {
        val departureMatches = hasConfirmedStationDisplay(root, null, SearchField.DEPARTURE, from)
        val arrivalMatches = hasConfirmedStationDisplay(root, null, SearchField.ARRIVAL, to)
        val departureIsOpposite = hasConfirmedStationDisplay(root, null, SearchField.DEPARTURE, to)
        val arrivalIsOpposite = hasConfirmedStationDisplay(root, null, SearchField.ARRIVAL, from)
        val missing = buildList {
            if (!departureMatches && !departureIsOpposite) add("出发站:$from")
            if (!arrivalMatches && !arrivalIsOpposite) add("到达站:$to")
        }
        val conflicts = buildList {
            if (departureIsOpposite) add("出发站当前为到达站:$to")
            if (arrivalIsOpposite) add("到达站当前为出发站:$from")
        }
        return when {
            conflicts.isNotEmpty() -> SearchContextCheck(SearchContextStatus.CONFLICT, missing, conflicts)
            missing.isNotEmpty() -> SearchContextCheck(SearchContextStatus.MISSING, missing, conflicts)
            else -> SearchContextCheck(SearchContextStatus.MATCH)
        }
    }

    fun fillStation(
        root: AccessibilityNodeInfo,
        field: SearchField,
        stationName: String
    ): InteractionResult {
        prepareStationFlow(field, stationName)
        val formInput = findEditableField(root, field)
        val pickerInput = findStationPickerInput(root)
        val pickerVisible = isStationPickerVisible(root, field, stationName, formInput) ||
            pickerInput?.let { stationPickerInputBelongsToPicker(root, it, formInput) } == true
        // Once the station picker is visible, the normal form EditText is no
        // longer an eligible target. A picker without a strictly identified
        // top search field is evidence-incomplete and must not be clicked.
        var input = if (pickerVisible) pickerInput else formInput
        if (pickerVisible && input == null) {
            record("已检测到车站选择页，但未确认顶部搜索框")
            return InteractionResult.WAITING
        }
        if (!pickerVisible && stationPhase != StationSelectionPhase.IDLE && input == null) {
            record("车站选择流程已开始，但当前页面没有可确认的输入框")
            return InteractionResult.WAITING
        }
        if (pickerVisible && input != null && !rememberStationPickerInput(input)) {
            record("车站选择页顶部搜索框已变化，等待重新确认")
            return InteractionResult.WAITING
        }
        val inputKey = input?.let(::stationPickerInputKey)
        val currentValue = input?.text?.toString().orEmpty()
        val currentFingerprint = stationSnapshotFingerprint(root)
        if (pickerVisible) stationPickerSeen = true

        // The default picker list can already contain the requested station.
        // Always complete query write/readback before scanning or clicking it.
        if (input != null && !stationQueryConfirmed && !stationInputRequiresFreshWrite &&
            stationCandidateMatches(currentValue, stationName)
        ) {
            stationQueryConfirmed = true
            stationPhase = StationSelectionPhase.WAITING_FILTERED_CANDIDATE
            record("${field.actionName()}顶部搜索框已回读确认")
        }
        if (stationQueryWriteSent && !stationQueryConfirmed) {
            stationPhase = StationSelectionPhase.WAITING_QUERY_READBACK
            record("等待${field.actionName()}输入框回读目标站名")
            return InteractionResult.WAITING
        }

        if (input != null && (
                stationInputRequiresFreshWrite || stationQueryNeedsWrite(
                    currentValue,
                    stationName,
                    stationQueryWriteSent,
                    stationQueryConfirmed
                )
            )
        ) {
            if (!allowInput()) {
                record("${field.actionName()}输入动作已被接管闩锁阻止")
                return InteractionResult.FAILED
            }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, stationName)
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                record("${field.actionName()}输入框无法聚焦")
                return InteractionResult.FAILED
            }
            stationQueryBeforeWriteFingerprint = currentFingerprint
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                record("${field.actionName()}输入动作未派发")
                return InteractionResult.FAILED
            }
            stationPhase = StationSelectionPhase.WAITING_QUERY_READBACK
            stationActionSent = true
            stationQueryWriteSent = true
            stationQueryConfirmed = false
            stationInputRequiresFreshWrite = false
            stationQueryWriteInputKey = inputKey
            stationCandidateScope = if (pickerVisible) "PICKER_QUERY_SENT" else "FORM_QUERY_SENT"
            stationCandidateCount = 0
            record("已填写${field.actionName()}，等待输入框回读")
            return InteractionResult.WAITING
        }

        if (pickerVisible && !stationQueryConfirmed) {
            stationPhase = StationSelectionPhase.WAITING_QUERY_WRITE
            stationCandidateScope = "DEFAULT_PICKER_IGNORED"
            stationCandidateCount = 0
            record("等待${field.actionName()}搜索框写入目标站名，忽略默认常用车站")
            return InteractionResult.WAITING
        }

        val queryRefreshConfirmed = !pickerVisible ||
            stationQueryBeforeWriteFingerprint == null ||
            stationQueryBeforeWriteFingerprint != currentFingerprint
        if (pickerVisible && !queryRefreshConfirmed) {
            stationPhase = StationSelectionPhase.WAITING_FILTERED_CANDIDATE
            stationCandidateScope = "PICKER_WAITING_REFRESH"
            stationCandidateCount = 0
            record("等待${field.actionName()}查询后的候选列表刷新")
            return InteractionResult.WAITING
        }

        val candidates = findFieldScopedCandidates(root, input, field, stationName, pickerVisible)
        stationCandidateScope = if (pickerVisible) "FILTERED_PICKER" else "FORM_FIELD"
        stationCandidateCount = candidates.size
        if (candidates.isNotEmpty()) stationCandidateSeen = true
        val displayedStation = hasConfirmedStationDisplay(root, input, field, stationName)

        // ACTION_SET_TEXT only changes the query. It is not proof that the
        // picker row was selected, so wait for a post-click page refresh.
        if (stationPhase == StationSelectionPhase.WAITING_CONFIRMATION) {
            val snapshotChanged = stationBeforeClickFingerprint?.let { it != currentFingerprint } == true
            // A few WebView builds keep the selected row in the first form
            // tree after the picker closes. Confirm the field display before
            // treating that stale candidate as evidence that the picker is
            // still open.
            val formSelectionConfirmed = !pickerVisible &&
                (confirmedStationDisplay(root, input, field, stationName) || displayedStation)
            if (snapshotChanged && formSelectionConfirmed) {
                completeStationFlow()
                record("${field.actionName()}候选站已确认（表单字段已回填）")
                return InteractionResult.DONE
            }
            if (pickerVisible || (candidates.isNotEmpty() && !formSelectionConfirmed)) {
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
                    snapshotChanged,
                    fieldDisplayConfirmed = formSelectionConfirmed
                ) ||
                (snapshotChanged && (stationPickerSeen || stationCandidateSeen) &&
                    confirmedStationDisplay(root, input, field, stationName)) ||
                (snapshotChanged && stationActionSent && !pickerVisible && displayedStation)
            ) {
                completeStationFlow()
                record("${field.actionName()}候选站已确认")
                return InteractionResult.DONE
            }
            record("${field.actionName()}候选站已点击，等待字段值确认")
            return InteractionResult.WAITING
        }

        if (stationQueryWriteSent && stationQueryConfirmed && !pickerVisible &&
            queryRefreshConfirmed && displayedStation
        ) {
            completeStationFlow()
            record("${field.actionName()}已在官方表单显示并确认")
            return InteractionResult.DONE
        }

        // A pre-filled value is acceptable only when there is no active
        // picker. Values entered by ACTION_SET_TEXT are handled by the
        // explicit query readback path above.
        if (stationPhase == StationSelectionPhase.IDLE &&
            !pickerVisible &&
            candidates.isEmpty() &&
            (stationCandidateMatches(currentValue, stationName) || displayedStation)
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
            if (pickerVisible && !stationQueryConfirmed) {
                record("候选站已出现，但顶部搜索框尚未回读确认")
                return InteractionResult.WAITING
            }
            if (!clickNodeOrParent(candidates.single())) {
                record("${field.actionName()}候选站点击未派发")
                return InteractionResult.FAILED
            }
            stationPhase = StationSelectionPhase.WAITING_CONFIRMATION
            stationBeforeClickFingerprint = currentFingerprint
            stationActionSent = true
            record("已选择${field.actionName()}候选站，等待字段值确认")
            return InteractionResult.WAITING
        }

        if (stationPhase == StationSelectionPhase.WAITING_FILTERED_CANDIDATE ||
            stationPhase == StationSelectionPhase.WAITING_QUERY_READBACK ||
            stationPhase == StationSelectionPhase.WAITING_CANDIDATE
        ) {
            record("等待${field.actionName()}候选站（候选数=0）")
            return InteractionResult.WAITING
        }

        if (pickerVisible && stationQueryConfirmed) {
            stationPhase = StationSelectionPhase.WAITING_FILTERED_CANDIDATE
            record("等待${field.actionName()}过滤后的唯一候选站")
            return InteractionResult.WAITING
        }

        if (input != null) {
            stationPhase = StationSelectionPhase.WAITING_QUERY_WRITE
            record("等待${field.actionName()}输入框可用于查询")
            return InteractionResult.WAITING
        }

        if (stationPhase != StationSelectionPhase.IDLE) {
            record("等待${field.actionName()}车站选择页的严格输入证据")
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
        stationPhase = StationSelectionPhase.WAITING_QUERY_WRITE
        stationBeforeClickFingerprint = currentFingerprint
        stationActionSent = true
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
        stationActionSent = false
        stationQueryWriteSent = false
        stationQueryConfirmed = false
        stationQueryInputKey = null
        stationQueryWriteInputKey = null
        stationInputRequiresFreshWrite = false
        stationQueryBeforeWriteFingerprint = null
        stationCandidateScope = "NONE"
        stationCandidateCount = 0
    }

    private fun completeStationFlow() {
        stationField = null
        stationTarget = null
        stationPhase = StationSelectionPhase.IDLE
        stationPickerSeen = false
        stationCandidateSeen = false
        stationBeforeClickFingerprint = null
        stationActionSent = false
        stationQueryWriteSent = false
        stationQueryConfirmed = false
        stationQueryInputKey = null
        stationQueryWriteInputKey = null
        stationInputRequiresFreshWrite = false
        stationQueryBeforeWriteFingerprint = null
        stationCandidateScope = "NONE"
        stationCandidateCount = 0
    }

    private fun isStationPickerVisible(
        root: AccessibilityNodeInfo,
        field: SearchField,
        stationName: String,
        input: AccessibilityNodeInfo?
    ): Boolean {
        val markers = listOf("选择出发站", "选择到达站", "站点列表", "热门站点", "车站选择", "常用站点")
        val markerVisible = findNodes(root) { node ->
            node.isVisibleToUser && nodeValues(node).any { value ->
                val normalized = normalizeText(value)
                markers.any { marker ->
                    normalized.contains(marker) &&
                        (marker != "常用站点" || hasPickerAncestor(node))
                }
            }
        }.any { node ->
            hasPickerAncestor(node) ||
                nodeValues(node).any { value ->
                    val normalized = normalizeText(value)
                    listOf("选择出发站", "选择到达站", "站点列表", "热门站点", "车站选择")
                        .any(normalized::contains)
                }
        }
        if (markerVisible) return true
        if (stationPhase == StationSelectionPhase.IDLE) return false

        // Some builds omit picker titles entirely. A unique, clickable target
        // station outside the current form control is still strong picker
        // evidence, while the station already displayed in the form is not.
        val fieldControl = findStationFieldControl(root, field)
        return findStationCandidateNodes(root) { node ->
            nodeValues(node).any { stationCandidateMatches(it, stationName) }
        }.any { node ->
            val clickable = clickableNode(node) ?: return@any false
            val inInput = input?.let {
                isDescendantOrSelf(node, it) || isDescendantOrSelf(it, node)
            } == true
            val inFieldControl = fieldControl?.let {
                clickable === it || isDescendantOrSelf(node, it) || isDescendantOrSelf(it, node)
            } == true
            !inInput && !inFieldControl
        }
    }

    private fun findStationPickerInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = findNodes(root) { stationPickerInputDescriptor(root, it) != null }
            .distinctBy(::nodeIdentity)
        val marked = candidates.filter { node ->
            stationPickerInputDescriptor(root, node)?.let { stationPickerInputConfirmed(it) } == true
        }
        if (marked.isNotEmpty()) return marked.singleOrNull()

        // The 12306 picker sometimes exposes no hint, content description or
        // resource label at all. Once the page itself is proven to be a
        // station picker, a single top, wide, visible EditText is still a
        // strong structural match; arbitrary editable nodes are not accepted.
        val pageEvidence = hasStationPickerPageEvidence(root)
        val structural = candidates.filter { node ->
            stationPickerInputDescriptor(root, node)?.let { stationPickerInputLayoutConfirmed(it) } == true
        }
        if (pageEvidence && structural.size == 1) return structural.single()

        // After ACTION_SET_TEXT the hint may disappear. The exact same
        // previously confirmed node identity remains valid for readback.
        val known = structural.filter { stationQueryInputKey != null && stationPickerInputKey(it) == stationQueryInputKey }
        return known.singleOrNull()
    }

    private fun hasStationPickerPageEvidence(root: AccessibilityNodeInfo): Boolean {
        val markers = listOf("选择出发站", "选择到达站", "站点列表", "热门站点", "车站选择", "常用站点")
        val markerVisible = findNodes(root) { node ->
            node.isVisibleToUser && nodeValues(node).any { value ->
                val normalized = normalizeText(value)
                markers.any { marker -> normalized.contains(marker) }
            }
        }.isNotEmpty()
        if (markerVisible) return true
        return findNodes(root) { node -> node.isVisibleToUser && hasPickerAncestor(node) }.isNotEmpty()
    }

    private fun stationPickerInputBelongsToPicker(
        root: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo,
        formInput: AccessibilityNodeInfo?
    ): Boolean {
        if (hasPickerAncestor(input)) return true
        val pickerMarkers = listOf("选择出发站", "选择到达站", "站点列表", "热门站点", "车站选择")
        if (findNodes(root) { node ->
                node.isVisibleToUser && nodeValues(node).any { value ->
                    val normalized = normalizeText(value)
                    pickerMarkers.any { marker -> normalized.contains(marker) }
                }
            }.isNotEmpty()
        ) return true
        // When the picker omits all labels, the strict top input is accepted
        // only after the normal form control has disappeared.
        return formInput == null && stationPhase != StationSelectionPhase.IDLE
    }

    private fun stationPickerInputDescriptor(
        root: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo
    ): StationPickerInputDescriptor? {
        val rootBounds = Rect()
        val nodeBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        node.getBoundsInScreen(nodeBounds)
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0 ||
            nodeBounds.width() <= 0 || nodeBounds.height() <= 0
        ) return null
        return StationPickerInputDescriptor(
            editable = node.isEditable,
            visible = node.isVisibleToUser,
            topFraction = (nodeBounds.top - rootBounds.top).toFloat() / rootBounds.height(),
            widthFraction = nodeBounds.width().toFloat() / rootBounds.width(),
            semanticText = nodeValues(node).joinToString(" "),
            value = node.text?.toString().orEmpty()
        )
    }

    private fun rememberStationPickerInput(input: AccessibilityNodeInfo): Boolean {
        val key = stationPickerInputKey(input)
        val previous = stationQueryInputKey
        if (previous != null && previous != key) {
            // WebView rebuilds can replace the EditText or change its bounds
            // after a query. Rebind safely, but never trust the old node's
            // write/readback evidence on the new node.
            stationQueryInputKey = key
            stationQueryWriteInputKey = null
            stationQueryBeforeWriteFingerprint = null
            stationQueryWriteSent = false
            stationQueryConfirmed = false
            stationInputRequiresFreshWrite = true
            if (stationPhase != StationSelectionPhase.IDLE &&
                stationPhase != StationSelectionPhase.WAITING_CONFIRMATION
            ) {
                stationPhase = StationSelectionPhase.WAITING_QUERY_WRITE
            }
            stationCandidateScope = "INPUT_REBOUND"
            stationCandidateCount = 0
            record("车站搜索框节点已重绑，要求重新写入并回读")
            return true
        }
        stationQueryInputKey = key
        return true
    }

    private fun stationPickerInputKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val uniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.uniqueId.orEmpty()
        } else {
            ""
        }
        val parent = node.parent
        val parentBounds = Rect()
        parent?.getBoundsInScreen(parentBounds)
        val parentKey = listOf(
            parent?.viewIdResourceName.orEmpty(),
            parent?.className?.toString().orEmpty(),
            parentBounds.left,
            parentBounds.top
        ).joinToString("|")
        return listOf(
            uniqueId,
            node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(),
            bounds.left,
            bounds.top,
            parentKey
        ).joinToString("|")
    }

    private fun hasConfirmedStationDisplay(
        root: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo?,
        field: SearchField,
        target: String
    ): Boolean {
        val container = input?.let { findFieldContainer(it, field) }
        val control = findStationFieldControl(root, field)
        val scoped = findNodes(root) { node ->
            if (!node.isVisibleToUser || node.isEditable) return@findNodes false
            if (!nodeValues(node).any { stationCandidateMatches(it, target) }) return@findNodes false
            val directAssociation = fieldContextKind(nodeValues(node).joinToString(" "), field)
            val association = if (directAssociation == FieldContext.NONE) {
                nearestFieldContext(node, field)
            } else {
                directAssociation
            }
            if (association == FieldContext.OPPOSITE || association == FieldContext.AMBIGUOUS) return@findNodes false
            val inContainer = container?.let { isDescendantOrSelf(node, it) } == true
            val clickable = clickableNode(node)
            val inControl = control?.let {
                clickable === it || isDescendantOrSelf(node, it) || isDescendantOrSelf(it, node)
            } == true
            association == FieldContext.TARGET || inContainer || inControl
        }
        if (scoped.isNotEmpty()) return true

        // Do not fall back to an arbitrary occurrence elsewhere on the page.
        // The form may contain query history, ads, or the opposite station;
        // none of those proves that this field has been filled.
        return false
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
            val directAssociation = fieldContextKind(nodeValues(node).joinToString(" "), field)
            if (directAssociation == FieldContext.OPPOSITE || directAssociation == FieldContext.AMBIGUOUS) return@findNodes false
            val inContainer = container?.let { isDescendantOrSelf(node, it) } == true
            inContainer || directAssociation == FieldContext.TARGET || nearestFieldContext(node, field) == FieldContext.TARGET
        }.isNotEmpty()
    }

    fun fillDate(root: AccessibilityNodeInfo, date: String): InteractionResult {
        prepareDateFlow(date)
        val currentFingerprint = stationSnapshotFingerprint(root)
        val input = findEditableField(root, SearchField.DATE)
        val pickerVisible = isDatePickerVisible(root)
        if (datePhase == DateSelectionPhase.WAITING_CONFIRMATION) {
            val currentValue = input?.text?.toString().orEmpty()
            val snapshotChanged = dateBeforeClickFingerprint?.let { it != currentFingerprint } == true
            val fieldConfirmed = !pickerVisible &&
                (matchesTravelDate(currentValue, date) || hasConfirmedDateDisplay(root, date))
            if (dateSelectionConfirmed(
                    datePhase,
                    currentValue,
                    dateFieldContextText(root, input),
                    date,
                    pickerVisible,
                    snapshotChanged
                ) || fieldConfirmed
            ) {
                completeDateFlow()
                record("乘车日期候选已确认")
                return InteractionResult.DONE
            }
            record(
                if (pickerVisible) {
                    "等待乘车日期选择器关闭（快照变化=$snapshotChanged）"
                } else {
                    "等待乘车日期字段刷新（快照变化=$snapshotChanged）"
                }
            )
            return InteractionResult.WAITING
        }

        if (datePhase == DateSelectionPhase.WAITING_PICKER && !pickerVisible) {
            val currentValue = input?.text?.toString().orEmpty()
            if (matchesTravelDate(currentValue, date) || hasConfirmedDateDisplay(root, date)) {
                completeDateFlow()
                record("乘车日期已在官方表单显示")
                return InteractionResult.DONE
            }
            val snapshotChanged = datePickerLastFingerprint?.let { it != currentFingerprint } == true
            if (snapshotChanged) {
                datePickerWaitEvents = 0
                datePickerLastFingerprint = currentFingerprint
            } else {
                datePickerWaitEvents++
            }
            if (!snapshotChanged &&
                datePickerWaitEvents >= DATE_PICKER_OPEN_RETRY_AFTER_EVENTS &&
                datePickerOpenRetries < MAX_DATE_PICKER_OPEN_RETRIES
            ) {
                val controls = findDateControls(root)
                when {
                    controls.size > 1 -> {
                        record("日期选择器未打开，日期控件不唯一（${controls.size} 个）")
                        return InteractionResult.FAILED
                    }
                    controls.size == 1 -> {
                        if (!clickNodeOrParent(controls.single())) {
                            record("日期选择器重试打开动作未派发")
                            return InteractionResult.FAILED
                        }
                        datePickerOpenRetries++
                        datePickerWaitEvents = 0
                        datePickerLastFingerprint = currentFingerprint
                        dateBeforeClickFingerprint = currentFingerprint
                        record("日期选择器未出现，已第 ${datePickerOpenRetries} 次重试打开")
                        return InteractionResult.WAITING
                    }
                }
            }
            record("等待日期选择器打开（等待=${datePickerWaitEvents}，重试=${datePickerOpenRetries}）")
            return InteractionResult.WAITING
        }

        if (pickerVisible) {
            datePickerWaitEvents = 0
            datePickerLastFingerprint = currentFingerprint
        }

        // Only edit the form field when the picker is not covering it. When a
        // picker is visible, continue below and locate the target day cell.
        if (!pickerVisible && input != null) {
            val currentValue = input.text?.toString().orEmpty()
            if (matchesTravelDate(currentValue, date)) {
                record("乘车日期已是目标日期")
                return InteractionResult.DONE
            }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, date)
            }
            if (!allowInput()) {
                record("日期输入动作已被接管闩锁阻止")
                return InteractionResult.FAILED
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                record("日期输入框无法聚焦")
                return InteractionResult.FAILED
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                record("日期输入动作未派发")
                return InteractionResult.FAILED
            }
            datePhase = DateSelectionPhase.WAITING_CONFIRMATION
            dateBeforeClickFingerprint = currentFingerprint
            record("已填写乘车日期，等待页面更新")
            return InteractionResult.WAITING
        }

        if (datePhase == DateSelectionPhase.IDLE && !pickerVisible && hasConfirmedDateDisplay(root, date)) {
            completeDateFlow()
            record("乘车日期已在官方表单显示")
            return InteractionResult.DONE
        }

        val calendarDateNodes = findTargetCalendarDateNodes(root, date)
        if (calendarDateNodes.size == 1) {
            val target = calendarDateNodes.single()
            if (!clickNodeOrParent(target.control)) {
                record("目标日期点击未派发")
                return InteractionResult.FAILED
            }
            datePhase = DateSelectionPhase.WAITING_CONFIRMATION
            datePickerWaitEvents = 0
            datePickerLastFingerprint = currentFingerprint
            dateBeforeClickFingerprint = currentFingerprint
            record(
                if (target.selected) {
                    "目标日期已处于选中状态，已重新点击以关闭日期选择器"
                } else {
                    "已选择目标日期，等待页面更新"
                }
            )
            return InteractionResult.WAITING
        }
        if (calendarDateNodes.size > 1) {
            record("目标日期控件不唯一（当前月份候选数=${calendarDateNodes.size}）")
            return InteractionResult.FAILED
        }

        // Some builds expose the complete date as the cell content instead of
        // a numeric day. Keep this fallback, but never use an arbitrary day
        // number without the month context collected above.
        val exactDateNodes = findNodes(root) { node ->
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                value.any { text -> dateVariants(date).any { normalizeDateText(it) == normalizeDateText(text) } }
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        if (exactDateNodes.size == 1) {
            if (!clickNodeOrParent(exactDateNodes.single())) return InteractionResult.FAILED
            datePhase = DateSelectionPhase.WAITING_CONFIRMATION
            dateBeforeClickFingerprint = currentFingerprint
            record("已选择目标日期，等待页面更新")
            return InteractionResult.WAITING
        }
        if (exactDateNodes.size > 1) {
            record("目标日期控件不唯一")
            return InteractionResult.FAILED
        }

        if (pickerVisible) {
            record("日期选择器已打开，但未找到带月份上下文的目标日期")
            return InteractionResult.WAITING
        }

        val controls = findDateControls(root)
        if (controls.size == 1) {
            if (!clickNodeOrParent(controls.single())) return InteractionResult.FAILED
            datePhase = DateSelectionPhase.WAITING_PICKER
            dateBeforeClickFingerprint = currentFingerprint
            datePickerWaitEvents = 0
            datePickerOpenRetries = 0
            datePickerLastFingerprint = currentFingerprint
            record("已打开日期选择器，等待日历日期")
            return InteractionResult.WAITING
        }
        if (controls.size > 1) {
            record("日期控件不唯一")
            return InteractionResult.FAILED
        }
        return InteractionResult.WAITING
    }

    private data class CalendarDateNode(
        val control: AccessibilityNodeInfo,
        val selected: Boolean
    )

    /**
     * Current 12306 builds may expose calendar cells as only "19" plus a
     * lunar label. Track month headers in sibling order so September 19 is
     * not confused with the October 19 cell shown in the same tree.
     */
    private fun findTargetCalendarDateNodes(
        root: AccessibilityNodeInfo,
        date: String
    ): List<CalendarDateNode> {
        val byControl = linkedMapOf<Int, CalendarDateNode>()
        val numericDayCandidates = linkedMapOf<Int, CalendarDateNode>()
        val fallbackYear = findCalendarYear(root)

        fun walkChildren(parent: AccessibilityNodeInfo?, inheritedMonth: String?) {
            if (parent == null) return
            var activeMonth = inheritedMonth
            for (index in 0 until parent.childCount) {
                val child = parent.getChild(index) ?: continue
                val header = nodeValues(child).firstOrNull { isCalendarMonthHeader(it) }
                if (header != null) activeMonth = header
                val values = nodeValues(child)
                val clickable = clickableNode(child)
                val visibleOrInteractive = child.isVisibleToUser || child.isClickable ||
                    clickable?.isVisibleToUser == true
                if (visibleOrInteractive && clickable != null &&
                    values.any { calendarDayMatches(it, date) }
                ) {
                    val key = nodeIdentity(clickable)
                    val candidate = CalendarDateNode(clickable, isSelectedDateNode(child))
                    val previousNumeric = numericDayCandidates[key]
                    if (previousNumeric == null || (!previousNumeric.selected && candidate.selected)) {
                        numericDayCandidates[key] = candidate
                    }
                }
                if (visibleOrInteractive && activeMonth != null && clickable != null &&
                    values.any { calendarDateCellMatches(it, activeMonth, date, fallbackYear) }
                ) {
                    val key = nodeIdentity(clickable)
                    val candidate = CalendarDateNode(clickable, isSelectedDateNode(child))
                    val previous = byControl[key]
                    if (previous == null || (!previous.selected && candidate.selected)) {
                        byControl[key] = candidate
                    }
                }
                walkChildren(child, activeMonth)
            }
        }

        walkChildren(root, null)
        if (byControl.isNotEmpty()) return byControl.values.toList()
        // If a build omits month headers from the accessibility tree, a
        // single selected target day is still safe evidence; an unselected
        // numeric day remains ambiguous across adjacent months.
        val selectedFallback = numericDayCandidates.values.filter { it.selected }
        return if (selectedFallback.size == 1) selectedFallback else emptyList()
    }

    private fun isCalendarMonthHeader(value: String): Boolean {
        return parseCalendarMonthContext(value) != null
    }

    private fun findCalendarYear(root: AccessibilityNodeInfo): Int? {
        val year = Regex("(?<!\\d)(\\d{4})年").find(rootText(root))?.groupValues?.getOrNull(1)
            ?: Regex("(?<!\\d)(\\d{4})[-/]\\d{1,2}").find(rootText(root))?.groupValues?.getOrNull(1)
        return year?.toIntOrNull()
    }

    private fun hasCalendarGridEvidence(root: AccessibilityNodeInfo): Boolean {
        val dayLabels = findNodes(root) { node ->
            val control = clickableNode(node)
            val visibleOrInteractive = node.isVisibleToUser || node.isClickable || control?.isVisibleToUser == true
            visibleOrInteractive && control != null && nodeValues(node).any(::looksLikeCalendarDayLabel)
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        return dayLabels.size >= MIN_CALENDAR_DAY_NODES &&
            dayLabels.map { nodeValues(it).joinToString(" ") }.distinct().size >= MIN_CALENDAR_DISTINCT_DAYS
    }

    private fun looksLikeCalendarDayLabel(value: String): Boolean {
        val normalized = normalizeDateText(value)
        val marker = "(?:日|号|今天|明天|后天|(?:周|星期)[一二三四五六日天]|初[一二三四五六七八九十]|十[一二三四五六七八九十]|廿[一二三四五六七八九十]|卅[一二三四五六七八九十])"
        return Regex("^(?:$marker)*0?(?:[1-9]|[12]\\d|3[01])(?:$marker)*$")
            .matches(normalized)
    }

    private fun isSelectedDateNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_DEPTH + 1) {
            if (current == null) return@repeat
            if (current?.isSelected == true || current?.isChecked == true) return true
            if (nodeValues(current!!).any { value ->
                    val normalized = normalizeText(value)
                    normalized.contains("已选") || normalized.contains("选中")
                }
            ) return true
            current = current?.parent
        }
        return false
    }

    private fun hasConfirmedDateDisplay(root: AccessibilityNodeInfo, date: String): Boolean {
        val values = findNodes(root) { node ->
            if (!node.isVisibleToUser || node.isEditable) return@findNodes false
            matchesTravelDate(nodeValues(node).joinToString(" "), date)
        }
        val scoped = values.filter { nearestFieldContext(it, SearchField.DATE) == FieldContext.TARGET }
        if (scoped.isNotEmpty()) return true
        val normalized = normalizeText(rootText(root))
        val formEvidence = listOf("出发地", "出发站", "到达地", "到达站")
            .count(normalized::contains) >= 2 &&
            listOf("查询", "查询车票", "搜索车票").any(normalized::contains)
        val displayNodes = values.filter { it.childCount == 0 || it.isClickable }
        val displayControls = displayNodes.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)
        return dateDisplayFallbackConfirmed(
            formEvidence = formEvidence,
            pickerVisible = isDatePickerVisible(root),
            displayControlCount = displayControls.size,
            displayLeafCount = displayNodes.size
        )
    }

    private fun dateFieldContextText(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): String {
        val values = mutableListOf<String>()
        if (input != null) {
            values += nodeValues(input)
            var parent = input.parent
            repeat(MAX_PARENT_DEPTH) {
                if (parent == null) return@repeat
                val parentValues = nodeValues(parent)
                values += parentValues
                if (fieldContextKind(parentValues.joinToString(" "), SearchField.DATE) == FieldContext.TARGET) {
                    parent = null
                } else {
                    parent = parent.parent
                }
            }
        }
        val scoped = findNodes(root) { node ->
            !node.isEditable && node.isVisibleToUser &&
                nearestFieldContext(node, SearchField.DATE) == FieldContext.TARGET
        }
        values += scoped.flatMap(::nodeValues)
        return values.distinct().joinToString(" ")
    }

    private fun prepareDateFlow(date: String) {
        if (dateTarget == date) return
        dateTarget = date
        datePhase = DateSelectionPhase.IDLE
        dateBeforeClickFingerprint = null
        datePickerWaitEvents = 0
        datePickerOpenRetries = 0
        datePickerLastFingerprint = null
    }

    private fun completeDateFlow() {
        dateTarget = null
        datePhase = DateSelectionPhase.IDLE
        dateBeforeClickFingerprint = null
        datePickerWaitEvents = 0
        datePickerOpenRetries = 0
        datePickerLastFingerprint = null
    }

    private fun isDatePickerVisible(root: AccessibilityNodeInfo): Boolean {
        val text = rootText(root).replace(Regex("\\s+"), "")
        if (listOf("日期选择", "选择日期", "选择乘车日期", "日历", "上一月", "下一月")
                .any(text::contains)
        ) return true
        if (findNodes(root) { node -> nodeValues(node).any(::isCalendarMonthHeader) }.isNotEmpty()) return true
        return hasCalendarGridEvidence(root)
    }

    private fun findDateControls(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findNodes(root) { node ->
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString())
                .any { fieldLabelMatches(it, SearchField.DATE) }
        }.mapNotNull(::clickableNode).distinctBy(::nodeIdentity)

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
        val fieldContainer = input?.let { findFieldContainer(it, field) }
        val fieldControl = findStationFieldControl(root, field)
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
            val inFieldControl = fieldControl?.let {
                clickable === it || isDescendantOrSelf(stationNode, it) || isDescendantOrSelf(it, stationNode)
            } == true
            if (inFieldControl && !pickerVisible) return@mapNotNull null
            val association = nearestFieldContext(stationNode, field)
            val inContainer = fieldContainer?.let { isDescendantOrSelf(stationNode, it) } == true
            val pickerScoped = pickerVisible || hasPickerAncestor(stationNode) ||
                (stationPhase != StationSelectionPhase.IDLE && !inFieldControl)
            when {
                association == FieldContext.OPPOSITE || association == FieldContext.AMBIGUOUS -> null
                association == FieldContext.TARGET && pickerScoped && (pickerVisible || fieldContainer == null || inContainer) -> clickable
                association == FieldContext.NONE && pickerScoped && stationField == field -> clickable
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

    private fun hasPickerAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_DEPTH + 2) {
            if (current == null) return@repeat
            val marker = (nodeValues(current).joinToString(" ") + " " + current.className)
                .lowercase()
            if (listOf("picker", "stationlist", "station_picker", "站点列表", "热门站点", "车站选择", "dialog", "popup", "list")
                    .any(marker::contains)
            ) return true
            current = current.parent
        }
        return false
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
        if (action == SearchAction.SUBMIT_SEARCH) {
            val ranked = candidates.mapNotNull { candidate ->
                val priority = candidate.labels.maxOfOrNull(::searchSubmitLabelPriority) ?: 0
                candidate.takeIf { priority > 0 }?.let { priority to it }
            }
            val highest = ranked.maxOfOrNull { it.first }
            if (highest != null) {
                return ranked.filter { it.first == highest }
                    .map { it.second.control }
                    .singleOrNull()
            }
        }
        val preferred = when (action) {
            SearchAction.OPEN_TICKETS -> candidates.filter { candidate ->
                candidate.labels.any { value ->
                    val normalized = normalizeText(value)
                    normalized.contains("查询车票") || normalized.contains("余票查询") || normalized == "火车票"
                }
            }
            SearchAction.SUBMIT_SEARCH -> emptyList()
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
        if (!allowInput()) return false
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
        return lightweightAccessibilityFingerprint(root)
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
        private const val DATE_PICKER_OPEN_RETRY_AFTER_EVENTS = 5
        private const val MAX_DATE_PICKER_OPEN_RETRIES = 2
        private const val MIN_CALENDAR_DAY_NODES = 14
        private const val MIN_CALENDAR_DISTINCT_DAYS = 7
    }

    private enum class FieldContext { NONE, TARGET, OPPOSITE, AMBIGUOUS }
}
