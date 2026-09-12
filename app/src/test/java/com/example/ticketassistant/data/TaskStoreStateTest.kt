package com.example.ticketassistant.data

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStoreStateTest {
    @Test
    fun `query stages are active and snapshot changes are detectable`() {
        val task = TicketTask(
            taskId = "task-1",
            date = "2026-09-19",
            from = Station("汉口", "HKN"),
            to = Station("潜江", "QJN"),
            train = Train("D637", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
            seat = "二等座",
            passengerName = "测试乘客",
            saleDateTime = null
        )

        assertTrue(TaskStatus.OPENING_SEARCH.isAccessibilityActive())
        assertTrue(TaskStatus.SUBMITTING_SEARCH.isAccessibilityActive())
        assertTrue(TaskStatus.PREPARING.isAccessibilityActive())
        assertTrue(TaskStatus.SALE_T0.isAccessibilityActive())
        assertTrue(TaskStatus.COLD_START.isAccessibilityActive())
        assertTrue(TaskStatus.OPENING_OFFICIAL_APP.isAccessibilityActive())
        assertTrue(TaskStatus.OPENING_HOME.isAccessibilityActive())
        assertTrue(TaskStatus.FILLING_SEARCH_FORM.isAccessibilityActive())
        assertTrue(TaskStatus.WAITING_SEARCH_RESULT.isAccessibilityActive())
        assertNotEquals(taskSnapshotKey(task), taskSnapshotKey(task.copy(seat = "一等座")))
    }
}
