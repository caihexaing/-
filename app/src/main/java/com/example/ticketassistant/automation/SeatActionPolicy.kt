package com.example.ticketassistant.automation

/**
 * Chooses a seat action only when the accessibility tree gives us one
 * unambiguous target. The whole train card is intentionally not a fallback:
 * it cannot identify which seat the user configured.
 */
internal enum class SeatActionTarget {
    EXPLICIT_BOOKING,
    NONE,
    AMBIGUOUS
}

internal fun waitBudgetExpired(startedAt: Long, now: Long, timeoutMs: Long): Boolean =
    startedAt > 0L && timeoutMs > 0L && now >= startedAt && now - startedAt >= timeoutMs

internal fun shouldIgnorePopupDuringSeatAction(
    stageName: String,
    seatActionSent: Boolean
): Boolean = stageName == "SEAT" && seatActionSent

internal fun resolveSeatActionTarget(
    seatMatchCount: Int,
    explicitBookingCount: Int
): SeatActionTarget = when {
    seatMatchCount == 0 -> SeatActionTarget.NONE
    seatMatchCount != 1 -> SeatActionTarget.AMBIGUOUS
    explicitBookingCount > 1 -> SeatActionTarget.AMBIGUOUS
    explicitBookingCount == 1 -> SeatActionTarget.EXPLICIT_BOOKING
    else -> SeatActionTarget.NONE
}
