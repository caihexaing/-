package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollingPolicyTest {
    private val policy = PollingPolicy()

    @Test fun `first thirty seconds use bounded short interval`() {
        assertEquals(4_000L, policy.nextDelayMillis(0))
        assertEquals(4_000L, policy.nextDelayMillis(29_999))
    }

    @Test fun `steady state uses slower interval`() {
        assertEquals(9_000L, policy.nextDelayMillis(30_000))
    }

    @Test fun `stops at maximum duration`() {
        assertNull(policy.nextDelayMillis(2 * 60 * 60 * 1_000L))
    }
}
