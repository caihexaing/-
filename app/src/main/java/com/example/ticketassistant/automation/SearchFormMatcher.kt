package com.example.ticketassistant.automation

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class SearchField {
    DEPARTURE,
    ARRIVAL,
    DATE
}

enum class SearchAction {
    OPEN_TICKETS,
    SELECT_DEPARTURE,
    SELECT_ARRIVAL,
    OPEN_DATE,
    SUBMIT_SEARCH
}

enum class InteractionResult {
    DONE,
    WAITING,
    FAILED
}

enum class SearchStage {
    OPEN_SEARCH,
    DEPARTURE,
    ARRIVAL,
    DATE,
    SUBMIT_SEARCH,
    RESULT
}

enum class SearchContextStatus { MATCH, MISSING, CONFLICT, WRONG_PAGE }

data class SearchContextCheck(
    val status: SearchContextStatus,
    val missing: List<String> = emptyList(),
    val conflicts: List<String> = emptyList()
)

/** Classifies the current result-page evidence without allowing a missing field to become a mismatch. */
fun classifySearchContext(text: String, task: com.example.ticketassistant.data.TicketTask): SearchContextCheck {
    if (detectOfficialPageState(text) != OfficialPageState.SEARCH_RESULT) {
        return SearchContextCheck(SearchContextStatus.WRONG_PAGE)
    }
    val normalized = normalizeText(text)
    val missing = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    val from = normalizeText(task.from.name)
    val to = normalizeText(task.to.name)
    val hasFrom = normalized.contains(from)
    val hasTo = normalized.contains(to)
    if (!hasFrom) missing += "出发站:${task.from.name}"
    if (!hasTo) missing += "到达站:${task.to.name}"
    if (hasFrom.xor(hasTo)) conflicts += "路线字段不完整"
    if (hasFrom && hasTo && normalized.indexOf(from) > normalized.indexOf(to)) {
        conflicts += "路线顺序不一致"
    }
    val dateMatches = matchesTravelDate(text, task.date)
    val hasDateToken = Regex("(?:\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}月\\d{1,2}日)")
        .containsMatchIn(text.replace(Regex("\\s+"), ""))
    when {
        dateMatches -> Unit
        hasDateToken -> conflicts += "乘车日期不一致"
        else -> missing += "乘车日期:${task.date}"
    }
    if (!matchesToken(text, task.train.trainNo)) {
        val trainLike = Regex("(?<![A-Za-z0-9])[gcdztksylpn]\\d{1,4}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
        if (trainLike) conflicts += "车次不一致:${task.train.trainNo}" else missing += "车次:${task.train.trainNo}"
    }
    if (!normalized.contains(normalizeText(task.seat))) {
        val otherSeat = listOf("商务座", "一等座", "二等座", "高级软卧", "软卧", "硬卧", "硬座", "无座")
            .firstOrNull { normalized.contains(normalizeText(it)) }
        if (otherSeat == null) missing += "席别:${task.seat}" else conflicts += "席别不一致"
    }
    return when {
        conflicts.isNotEmpty() -> SearchContextCheck(SearchContextStatus.CONFLICT, missing, conflicts)
        missing.isNotEmpty() -> SearchContextCheck(SearchContextStatus.MISSING, missing, conflicts)
        else -> SearchContextCheck(SearchContextStatus.MATCH)
    }
}

fun nextSearchStage(stage: SearchStage, page: OfficialPageState, action: InteractionResult): SearchStage {
    if (action != InteractionResult.DONE) return stage
    return when (stage) {
        SearchStage.OPEN_SEARCH -> SearchStage.DEPARTURE
        SearchStage.DEPARTURE -> SearchStage.ARRIVAL
        SearchStage.ARRIVAL -> SearchStage.DATE
        SearchStage.DATE -> SearchStage.SUBMIT_SEARCH
        SearchStage.SUBMIT_SEARCH -> if (page == OfficialPageState.SEARCH_RESULT) SearchStage.RESULT else stage
        SearchStage.RESULT -> stage
    }
}

/** A testable projection of an accessibility node used by the search-form matcher. */
data class NodeDescriptor(
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val parentIndex: Int? = null,
    val contextText: String? = null
)

fun dateVariants(date: String): Set<String> {
    val parsed = runCatching { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        ?: return setOf(date)
    val year = parsed.year.toString()
    val month = parsed.monthValue.toString()
    val day = parsed.dayOfMonth.toString()
    val paddedMonth = "%02d".format(parsed.monthValue)
    val paddedDay = "%02d".format(parsed.dayOfMonth)
    return linkedSetOf(
        "$year-$paddedMonth-$paddedDay",
        "$year/$paddedMonth/$paddedDay",
        year + "年" + month + "月" + day + "日",
        year + "年" + paddedMonth + "月" + paddedDay + "日",
        month + "月" + day + "日",
        paddedMonth + "月" + paddedDay + "日",
        "$month/$day",
        "$paddedMonth/$paddedDay"
    )
}

fun exactStationCandidate(text: String, stationName: String): Boolean =
    normalizeStation(text) == normalizeStation(stationName)

/**
 * Matches station labels used by the picker without accepting arbitrary
 * substring matches. Some official builds append the display suffix "站".
 */
internal fun stationCandidateMatches(text: String, stationName: String): Boolean {
    val candidate = normalizeStation(text)
    val target = normalizeStation(stationName)
    if (candidate == target) return true
    return !target.endsWith("站") && candidate == target + "站"
}

internal enum class StationSelectionPhase {
    IDLE,
    WAITING_CANDIDATE,
    WAITING_CONFIRMATION
}

internal enum class DateSelectionPhase {
    IDLE,
    WAITING_CONFIRMATION
}

/** Input text alone is never proof that a picker selection was confirmed. */
internal fun stationSelectionConfirmed(
    phase: StationSelectionPhase,
    fieldValue: String,
    stationName: String,
    pickerVisible: Boolean,
    candidateCount: Int,
    pickerEvidenceSeen: Boolean,
    snapshotChanged: Boolean
): Boolean = phase == StationSelectionPhase.WAITING_CONFIRMATION &&
    pickerEvidenceSeen &&
    snapshotChanged &&
    !pickerVisible &&
    candidateCount == 0 &&
    stationCandidateMatches(fieldValue, stationName)

/** A typed date is not confirmation until the official page reflects the action. */
internal fun dateSelectionConfirmed(
    phase: DateSelectionPhase,
    fieldValue: String,
    pageText: String,
    date: String,
    pickerVisible: Boolean,
    snapshotChanged: Boolean
): Boolean = phase == DateSelectionPhase.WAITING_CONFIRMATION &&
    snapshotChanged &&
    !pickerVisible &&
    (matchesTravelDate(fieldValue, date) || matchesTravelDate(pageText, date))

fun matchesTravelDate(text: String, date: String): Boolean {
    val expected = parseDate(date) ?: return false
    val normalized = normalizeDateText(text)
    val explicitYears = Regex("(\\d{4})年|(?<!\\d)(\\d{4})[-/]\\d{1,2}[-/]\\d{1,2}")
        .findAll(normalized)
        .mapNotNull { it.groupValues.drop(1).firstOrNull { value -> value.isNotBlank() }?.toIntOrNull() }
        .toSet()
    if (explicitYears.isNotEmpty() && expected.year !in explicitYears) return false
    return dateVariants(date).any { normalizeDateText(it) in normalized }
}

/** The task only supports one adult passenger; never submit without this evidence. */
internal fun hasAdultTicketSelection(text: String): Boolean {
    val normalized = normalizeText(text)
    return normalized.contains("成人票") &&
        !normalized.contains("未选择成人票") &&
        !normalized.contains("请选择成人票")
}

fun fieldLabelMatches(text: String, field: SearchField): Boolean {
    val normalized = normalizeLabel(text)
    if (normalized.isBlank()) return false
    return when (field) {
        SearchField.DEPARTURE -> listOf("出发地", "出发站", "出发城市", "出发").any(normalized::contains)
        SearchField.ARRIVAL -> listOf("到达地", "到达站", "到达城市", "到达").any(normalized::contains)
        SearchField.DATE -> listOf("出发日期", "乘车日期", "出发日", "日期").any(normalized::contains)
    }
}

/**
 * Returns true only when a label/resource context belongs to one field.
 * A container that mentions both station fields is deliberately ambiguous.
 */
fun fieldContextMatches(text: String, field: SearchField): Boolean {
    val normalized = normalizeLabel(text)
    if (normalized.isBlank()) return false
    val target = fieldTargetLabelMatches(normalized, field) || resourceLabelMatches(normalized, field)
    val opposite = oppositeField(field)?.let {
        fieldTargetLabelMatches(normalized, it) || resourceLabelMatches(normalized, it)
    } == true
    return target && !opposite
}

fun candidateBelongsToField(contextText: String?, field: SearchField): Boolean =
    contextText?.let { fieldContextMatches(it, field) } == true

fun actionLabelMatches(text: String, action: SearchAction): Boolean {
    val normalized = normalizeLabel(text)
    if (normalized.isBlank()) return false
    return when (action) {
        SearchAction.OPEN_TICKETS -> listOf("车票", "查询车票", "余票查询").any(normalized::contains)
        SearchAction.SELECT_DEPARTURE -> fieldLabelMatches(normalized, SearchField.DEPARTURE)
        SearchAction.SELECT_ARRIVAL -> fieldLabelMatches(normalized, SearchField.ARRIVAL)
        SearchAction.OPEN_DATE -> fieldLabelMatches(normalized, SearchField.DATE)
        SearchAction.SUBMIT_SEARCH -> listOf("查询", "搜索车票", "查询车票", "余票查询").any(normalized::contains)
    }
}

fun findUniqueField(nodes: List<NodeDescriptor>, field: SearchField): NodeDescriptor? {
    val candidates = nodes.filter { node ->
        if (!node.editable) return@filter false
        val directValues = listOfNotNull(node.text, node.contentDescription, node.resourceId)
        val context = node.contextText.orEmpty()
        val directTarget = directValues.any { value -> fieldContextMatches(value, field) }
        val directOpposite = oppositeField(field)?.let { opposite ->
            directValues.any { value -> fieldTargetLabelMatches(value, opposite) || resourceLabelMatches(value, opposite) }
        } == true
        val contextTarget = fieldContextMatches(context, field)
        val contextAmbiguous = contextHasBothStationFields(context)
        node.editable && (directTarget || contextTarget) && !directOpposite && !contextAmbiguous
    }
    return candidates.singleOrNull()
}

fun findExactCandidate(
    nodes: List<NodeDescriptor>,
    stationName: String,
    field: SearchField? = null
): NodeDescriptor? {
    return nodes.filter { node ->
        node.clickable && listOfNotNull(node.text, node.contentDescription).any {
            exactStationCandidate(it, stationName)
        } && (field == null || candidateBelongsToField(node.contextText, field))
    }.singleOrNull()
}

internal fun normalizeStation(value: String): String = value.trim().replace(Regex("\\s+"), "")

private fun normalizeDateText(value: String): String = value.trim().replace(Regex("\\s+"), "")

private fun normalizeLabel(value: String): String = value.trim().replace(Regex("\\s+"), "").lowercase()

private fun parseDate(value: String): LocalDate? = try {
    LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
} catch (_: DateTimeParseException) {
    null
}

internal fun resourceLabelMatches(value: String, field: SearchField): Boolean {
    val normalized = normalizeLabel(value)
    return when (field) {
        SearchField.DEPARTURE -> listOf("departure", "fromstation", "from_city", "startstation", "start_city").any(normalized::contains)
        SearchField.ARRIVAL -> listOf("arrival", "tostation", "to_city", "endstation", "end_city").any(normalized::contains)
        SearchField.DATE -> listOf("date", "calendar", "travelday", "traindate").any(normalized::contains)
    }
}

private fun oppositeField(field: SearchField): SearchField? = when (field) {
    SearchField.DEPARTURE -> SearchField.ARRIVAL
    SearchField.ARRIVAL -> SearchField.DEPARTURE
    SearchField.DATE -> null
}

internal fun fieldTargetLabelMatches(text: String, field: SearchField): Boolean {
    val normalized = normalizeLabel(text)
    return when (field) {
        SearchField.DEPARTURE -> listOf("出发地", "出发站", "出发城市", "出发点", "出发")
            .any { normalized == it || (normalized.contains(it) && !normalized.contains("日期")) }
        SearchField.ARRIVAL -> listOf("到达地", "到达站", "到达城市", "到达点", "到达")
            .any { normalized == it || (normalized.contains(it) && !normalized.contains("日期")) }
        SearchField.DATE -> fieldLabelMatches(normalized, field)
    }
}

private fun contextHasBothStationFields(value: String): Boolean {
    val normalized = normalizeLabel(value)
    if (normalized.isBlank()) return false
    val hasDeparture = fieldTargetLabelMatches(normalized, SearchField.DEPARTURE) ||
        resourceLabelMatches(normalized, SearchField.DEPARTURE)
    val hasArrival = fieldTargetLabelMatches(normalized, SearchField.ARRIVAL) ||
        resourceLabelMatches(normalized, SearchField.ARRIVAL)
    return hasDeparture && hasArrival
}
