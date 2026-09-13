package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatActionPolicyTest {
    @Test
    fun `prefers an explicit booking control in the matching seat row`() {
        assertEquals(
            SeatActionTarget.EXPLICIT_BOOKING,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 1,
                seatNodeAvailable = true,
                rowContainerCount = 1
            )
        )
    }

    @Test
    fun `allows a visible seat node even when accessibility marks it non clickable`() {
        assertEquals(
            SeatActionTarget.SEAT_NODE,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 0,
                seatNodeAvailable = true,
                rowContainerCount = 0
            )
        )
    }

    @Test
    fun `uses a unique row container only after direct seat action is unavailable`() {
        assertEquals(
            SeatActionTarget.ROW_CONTAINER,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 0,
                seatNodeAvailable = false,
                rowContainerCount = 1
            )
        )
    }

    @Test
    fun `never falls back to the whole train card`() {
        assertEquals(
            SeatActionTarget.NONE,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 0,
                seatNodeAvailable = false,
                rowContainerCount = 0
            )
        )
    }

    @Test
    fun `ambiguous seat or action stops automatic selection`() {
        assertEquals(
            SeatActionTarget.AMBIGUOUS,
            resolveSeatActionTarget(
                seatMatchCount = 2,
                explicitBookingCount = 0,
                seatNodeAvailable = true,
                rowContainerCount = 0
            )
        )
        assertEquals(
            SeatActionTarget.AMBIGUOUS,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 2,
                seatNodeAvailable = true,
                rowContainerCount = 0
            )
        )
    }
}
