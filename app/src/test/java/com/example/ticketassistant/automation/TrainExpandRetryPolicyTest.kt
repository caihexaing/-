package com.example.ticketassistant.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainExpandRetryPolicyTest {
    @Test
    fun `retries once after the first card expansion has had time to refresh`() {
        assertFalse(shouldRetryTrainExpansion(1, 1_499L, 0))
        assertTrue(shouldRetryTrainExpansion(1, 1_500L, 0))
    }

    @Test
    fun `never retries after the bounded second attempt or when booking exists`() {
        assertFalse(shouldRetryTrainExpansion(2, 5_000L, 0))
        assertFalse(shouldRetryTrainExpansion(1, 5_000L, 1))
    }
}
