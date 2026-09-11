package com.example.ticketassistant.automation

/** Bounded cadence: normal polling is 15-30 seconds with gradual backoff. */
class PollingPolicy(
    private val firstWindowMillis: Long = 30_000L,
    private val secondWindowMillis: Long = 90_000L,
    private val initialIntervalMillis: Long = 15_000L,
    private val backoffIntervalMillis: Long = 20_000L,
    private val maximumIntervalMillis: Long = 30_000L,
    private val maximumRunMillis: Long = 2 * 60 * 60 * 1_000L
) {
    fun nextDelayMillis(elapsedMillis: Long): Long? = when {
        elapsedMillis < 0 || elapsedMillis >= maximumRunMillis -> null
        elapsedMillis < firstWindowMillis -> initialIntervalMillis
        elapsedMillis < secondWindowMillis -> backoffIntervalMillis
        else -> maximumIntervalMillis
    }
}
