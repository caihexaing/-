package com.example.ticketassistant.automation

/**
 * Allows one bounded re-dispatch of the target train summary action when the
 * result tree has no usable seat booking control yet. It never treats a seat
 * label or a card container as a booking action.
 */
internal fun shouldRetryTrainSummaryExpansion(
    summaryAttempts: Int,
    elapsedMs: Long,
    bookingActionCount: Int,
    maxAttempts: Int = 2,
    retryAfterMs: Long = 1_500L
): Boolean = summaryAttempts in 1 until maxAttempts &&
    elapsedMs >= retryAfterMs &&
    bookingActionCount == 0
