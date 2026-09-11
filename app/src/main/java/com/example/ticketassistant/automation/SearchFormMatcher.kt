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
        node.editable && listOfNotNull(node.text, node.contentDescription, node.resourceId, node.contextText)
            .any { value -> fieldLabelMatches(value, field) || resourceLabelMatches(value, field) }
    }
    return candidates.singleOrNull()
}

fun findExactCandidate(nodes: List<NodeDescriptor>, stationName: String): NodeDescriptor? {
    return nodes.filter { node ->
        node.clickable && listOfNotNull(node.text, node.contentDescription).any {
            exactStationCandidate(it, stationName)
        }
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

private fun resourceLabelMatches(value: String, field: SearchField): Boolean {
    val normalized = normalizeLabel(value)
    return when (field) {
        SearchField.DEPARTURE -> listOf("departure", "fromstation", "from_city", "startstation", "start_city").any(normalized::contains)
        SearchField.ARRIVAL -> listOf("arrival", "tostation", "to_city", "endstation", "end_city").any(normalized::contains)
        SearchField.DATE -> listOf("date", "calendar", "travelday", "traindate").any(normalized::contains)
    }
}
