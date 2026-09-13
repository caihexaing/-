package com.example.ticketassistant.automation

/**
 * Chooses a seat action only when the accessibility tree gives us one
 * unambiguous target. The whole train card is intentionally not a fallback:
 * it cannot identify which seat the user configured.
 */
internal enum class SeatActionTarget {
    EXPLICIT_BOOKING,
    SEAT_NODE,
    ROW_CONTAINER,
    NONE,
    AMBIGUOUS
}

internal fun resolveSeatActionTarget(
    seatMatchCount: Int,
    explicitBookingCount: Int,
    seatNodeAvailable: Boolean,
    rowContainerCount: Int
): SeatActionTarget = when {
    seatMatchCount == 0 -> SeatActionTarget.NONE
    seatMatchCount != 1 -> SeatActionTarget.AMBIGUOUS
    explicitBookingCount > 1 -> SeatActionTarget.AMBIGUOUS
    explicitBookingCount == 1 -> SeatActionTarget.EXPLICIT_BOOKING
    seatNodeAvailable -> SeatActionTarget.SEAT_NODE
    rowContainerCount > 1 -> SeatActionTarget.AMBIGUOUS
    rowContainerCount == 1 -> SeatActionTarget.ROW_CONTAINER
    else -> SeatActionTarget.NONE
}
