package com.example.ticketassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TaskTimingTest {
    @Test fun `sale date is independent from travel date`() {
        assertEquals(
            Instant.parse("2026-09-10T06:00:00Z"),
            TaskTiming.parseSaleDateTime("2026-09-10 14:00")
        )
    }

    @Test fun `time without date is rejected`() {
        assertNull(TaskTiming.parseSaleDateTime("14:00"))
    }

    @Test fun `past sale starts immediately`() {
        assertTrue(TaskTiming.shouldStartImmediately("2026-09-10 14:00", Instant.parse("2026-09-10T06:00:00Z")))
        assertFalse(TaskTiming.shouldStartImmediately("2026-09-10 14:01", Instant.parse("2026-09-10T06:00:00Z")))
    }
}
