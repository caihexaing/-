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

internal data class SeatTapPoint(val x: Float, val y: Float)

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

/**
 * Returns a safe point for a visual seat tap. The point is constrained to the
 * intersection of the exact seat node and its matching train card, and is
 * rejected when either rectangle is outside the screen.
 */
internal fun seatTapPoint(
    nodeLeft: Int,
    nodeTop: Int,
    nodeRight: Int,
    nodeBottom: Int,
    cardLeft: Int,
    cardTop: Int,
    cardRight: Int,
    cardBottom: Int,
    screenWidth: Int,
    screenHeight: Int
): SeatTapPoint? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    if (nodeRight <= nodeLeft || nodeBottom <= nodeTop) return null
    if (cardRight <= cardLeft || cardBottom <= cardTop) return null
    if (nodeLeft < 0 || nodeTop < 0 || nodeRight > screenWidth || nodeBottom > screenHeight) return null
    if (cardLeft < 0 || cardTop < 0 || cardRight > screenWidth || cardBottom > screenHeight) return null
    val left = maxOf(nodeLeft, cardLeft)
    val top = maxOf(nodeTop, cardTop)
    val right = minOf(nodeRight, cardRight)
    val bottom = minOf(nodeBottom, cardBottom)
    if (right <= left || bottom <= top) return null
    return SeatTapPoint(
        x = (left + right) / 2f,
        y = (top + bottom) / 2f
    )
}
