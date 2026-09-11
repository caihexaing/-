package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollingPolicyTest {
    private val policy = PollingPolicy()

    @Test fun `first thirty seconds use fifteen second interval`() {
        assertEquals(15_000L, policy.nextDelayMillis(0))
        assertEquals(15_000L, policy.nextDelayMillis(29_999))
    }

    @Test fun `backoff reaches twenty then thirty seconds`() {
        assertEquals(20_000L, policy.nextDelayMillis(30_000))
        assertEquals(20_000L, policy.nextDelayMillis(89_999))
        assertEquals(30_000L, policy.nextDelayMillis(90_000))
    }

    @Test fun `stops at maximum duration`() {
        assertNull(policy.nextDelayMillis(2 * 60 * 60 * 1_000L))
    }
}
