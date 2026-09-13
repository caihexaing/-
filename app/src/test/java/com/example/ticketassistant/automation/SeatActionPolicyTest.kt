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

    @Test
    fun `gesture fallback is constrained to the seat node and train card`() {
        val point = seatTapPoint(
            nodeLeft = 59,
            nodeTop = 1897,
            nodeRight = 346,
            nodeBottom = 1981,
            cardLeft = 38,
            cardTop = 1645,
            cardRight = 1221,
            cardBottom = 2002,
            screenWidth = 1260,
            screenHeight = 2800
        )
        assertEquals(202.5f, point?.x ?: -1f, 0f)
        assertEquals(1939f, point?.y ?: -1f, 0f)
    }

    @Test
    fun `gesture fallback rejects disjoint bounds and clips a partially visible card`() {
        val partiallyVisible = seatTapPoint(
            nodeLeft = -10,
            nodeTop = 100,
            nodeRight = 100,
            nodeBottom = 200,
            cardLeft = 0,
            cardTop = 0,
            cardRight = 500,
            cardBottom = 500,
            screenWidth = 1260,
            screenHeight = 2800
        )
        assertEquals(50f, partiallyVisible?.x ?: -1f, 0f)
        assertEquals(150f, partiallyVisible?.y ?: -1f, 0f)
        assertEquals(
            null,
            seatTapPoint(
                nodeLeft = 700,
                nodeTop = 100,
                nodeRight = 800,
                nodeBottom = 200,
                cardLeft = 0,
                cardTop = 0,
                cardRight = 500,
                cardBottom = 500,
                screenWidth = 1260,
                screenHeight = 2800
            )
        )
        val clipped = seatTapPoint(
            nodeLeft = 100,
            nodeTop = 100,
            nodeRight = 200,
            nodeBottom = 200,
            cardLeft = 0,
            cardTop = 0,
            cardRight = 1300,
            cardBottom = 500,
            screenWidth = 1260,
            screenHeight = 2800
        )
        assertEquals(150f, clipped?.x ?: -1f, 0f)
        assertEquals(150f, clipped?.y ?: -1f, 0f)
    }

    @Test
    fun `seat and passenger waits use a monotonic time budget`() {
        assertEquals(false, waitBudgetExpired(1_000L, 9_999L, 9_000L))
        assertEquals(true, waitBudgetExpired(1_000L, 10_000L, 9_000L))
        assertEquals(false, waitBudgetExpired(0L, 20_000L, 10_000L))
        assertEquals(false, waitBudgetExpired(10_000L, 9_000L, 1_000L))
    }

    @Test
    fun `seat panel never goes through ordinary popup dismissal`() {
        assertEquals(true, shouldIgnorePopupDuringSeatAction("SEAT", true, false))
        assertEquals(true, shouldIgnorePopupDuringSeatAction("SEAT", false, true))
        assertEquals(false, shouldIgnorePopupDuringSeatAction("PASSENGER", true, false))
        assertEquals(false, shouldIgnorePopupDuringSeatAction("SEAT", false, false))
    }
}
