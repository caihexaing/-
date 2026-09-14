package com.example.ticketassistant.automation

/**
 * Allows one bounded re-dispatch of the target train expansion action when the
 * result tree has no usable seat booking control yet. The action itself may be
 * the outer card or the narrow summary fallback; neither is a seat booking.
 */
internal fun shouldRetryTrainExpansion(
    summaryAttempts: Int,
    elapsedMs: Long,
    bookingActionCount: Int,
    maxAttempts: Int = 2,
    retryAfterMs: Long = 1_500L
): Boolean = summaryAttempts in 1 until maxAttempts &&
    elapsedMs >= retryAfterMs &&
    bookingActionCount == 0
