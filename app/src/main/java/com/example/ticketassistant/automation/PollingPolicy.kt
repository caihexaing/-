package com.example.ticketassistant.automation

/** Bounded cadence: never concurrent or sub-second polling. */
class PollingPolicy(
    private val initialWindowMillis: Long = 30_000L,
    private val initialIntervalMillis: Long = 4_000L,
    private val steadyIntervalMillis: Long = 9_000L,
    private val maximumRunMillis: Long = 2 * 60 * 60 * 1_000L
) {
    fun nextDelayMillis(elapsedMillis: Long): Long? = when {
        elapsedMillis < 0 || elapsedMillis >= maximumRunMillis -> null
        elapsedMillis < initialWindowMillis -> initialIntervalMillis
        else -> steadyIntervalMillis
    }
}
