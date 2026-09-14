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
                explicitBookingCount = 1
            )
        )
    }

    @Test
    fun `missing booking never falls back to seat text or row container`() {
        assertEquals(
            SeatActionTarget.NONE,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 0
            )
        )
    }

    @Test
    fun `ambiguous seat or action stops automatic selection`() {
        assertEquals(
            SeatActionTarget.AMBIGUOUS,
            resolveSeatActionTarget(
                seatMatchCount = 2,
                explicitBookingCount = 0
            )
        )
        assertEquals(
            SeatActionTarget.AMBIGUOUS,
            resolveSeatActionTarget(
                seatMatchCount = 1,
                explicitBookingCount = 2
            )
        )
    }

    @Test
    fun `seat and passenger waits use a monotonic time budget`() {
        assertEquals(false, waitBudgetExpired(1_000L, 9_999L, 9_000L))
        assertEquals(true, waitBudgetExpired(1_000L, 10_000L, 9_000L))
        assertEquals(false, waitBudgetExpired(0L, 20_000L, 10_000L))
        assertEquals(false, waitBudgetExpired(10_000L, 9_000L, 1_000L))
    }

    @Test
    fun `pending booking never goes through ordinary popup dismissal`() {
        assertEquals(true, shouldIgnorePopupDuringSeatAction("SEAT", true))
        assertEquals(false, shouldIgnorePopupDuringSeatAction("PASSENGER", true))
        assertEquals(false, shouldIgnorePopupDuringSeatAction("SEAT", false))
    }
}
