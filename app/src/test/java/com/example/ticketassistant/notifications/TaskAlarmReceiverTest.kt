package com.example.ticketassistant.notifications

import com.example.ticketassistant.data.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAlarmReceiverTest {
    @Test fun `sale alarm accepts active in-flight states`() {
        assertTrue(isSalePhaseEligible(TaskStatus.WAITING_FOR_SALE))
        assertTrue(isSalePhaseEligible(TaskStatus.PREPARING))
        assertTrue(isSalePhaseEligible(TaskStatus.SALE_T0))
        assertTrue(isSalePhaseEligible(TaskStatus.COLD_START))
        assertTrue(isSalePhaseEligible(TaskStatus.OPENING_OFFICIAL_APP))
        assertTrue(isSalePhaseEligible(TaskStatus.WAITING_SEARCH_RESULT))
        assertTrue(isSalePhaseEligible(TaskStatus.VALIDATING_SEARCH_RESULT))
    }

    @Test fun `sale alarm does not restart terminal states`() {
        assertFalse(isSalePhaseEligible(TaskStatus.TAKEOVER))
        assertFalse(isSalePhaseEligible(TaskStatus.PENDING_PAYMENT))
        assertFalse(isSalePhaseEligible(TaskStatus.RESULT_UNKNOWN))
    }
}
