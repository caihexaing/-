package com.example.ticketassistant.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TaskTiming {
    val zone: ZoneId = ZoneId.of(TASK_ZONE_ID)
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun parseSaleDateTime(value: String?): Instant? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDateTime.parse(it, formatter).atZone(zone).toInstant() }.getOrNull() }

    fun shouldStartImmediately(saleDateTime: String?, now: Instant = Instant.now()): Boolean =
        parseSaleDateTime(saleDateTime)?.let { !it.isAfter(now) } ?: false
}
