package com.example.ticketassistant.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleSubmitGateTest {
    @Test fun `only one submit acquisition succeeds`() {
        val gate = SingleSubmitGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isLocked())
        assertFalse(gate.tryAcquire())
    }
}
