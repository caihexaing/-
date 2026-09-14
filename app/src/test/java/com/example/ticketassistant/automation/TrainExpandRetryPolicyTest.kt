package com.example.ticketassistant.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainExpandRetryPolicyTest {
    @Test
    fun `retries once after the first expansion has had time to refresh`() {
        assertFalse(shouldRetryTrainSummaryExpansion(1, 1_499L, 0))
        assertTrue(shouldRetryTrainSummaryExpansion(1, 1_500L, 0))
    }

    @Test
    fun `never retries after the bounded second attempt or when booking exists`() {
        assertFalse(shouldRetryTrainSummaryExpansion(2, 5_000L, 0))
        assertFalse(shouldRetryTrainSummaryExpansion(1, 5_000L, 1))
    }
}
