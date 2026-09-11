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
    val target = fieldLabelMatches(normalized, field) || resourceLabelMatches(normalized, field)
    val opposite = oppositeField(field)?.let {
        fieldLabelMatches(normalized, it) || resourceLabelMatches(normalized, it)
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
        val directTarget = directValues.any { value ->
            fieldLabelMatches(value, field) || resourceLabelMatches(value, field)
        }
        val directOpposite = oppositeField(field)?.let { opposite ->
            directValues.any { value ->
                fieldLabelMatches(value, opposite) || resourceLabelMatches(value, opposite)
            }
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

private fun contextHasBothStationFields(value: String): Boolean {
    val normalized = normalizeLabel(value)
    if (normalized.isBlank()) return false
    val hasDeparture = fieldLabelMatches(normalized, SearchField.DEPARTURE) ||
        resourceLabelMatches(normalized, SearchField.DEPARTURE)
    val hasArrival = fieldLabelMatches(normalized, SearchField.ARRIVAL) ||
        resourceLabelMatches(normalized, SearchField.ARRIVAL)
    return hasDeparture && hasArrival
}
