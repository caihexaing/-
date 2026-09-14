package com.example.ticketassistant.diagnostics

import com.example.ticketassistant.data.SaleState
import com.example.ticketassistant.data.Station
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.Train
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DiagnosticReportTest {
    @Test fun `report contains execution diagnostics but redacts passenger name`() {
        val task = TicketTask(
            taskId = "task-1",
            date = "2026-09-19",
            from = Station("汉口", "HKN"),
            to = Station("潜江", "QJN"),
            train = Train("D353", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
            seat = "二等座",
            passengerName = "蔡贺翔",
            saleState = SaleState.ALREADY_ON_SALE,
            saleDateTime = null,
            status = TaskStatus.FILLING_DEPARTURE,
            lastPageState = "HOME_PAGE",
            lastAction = "已选择乘车人：蔡贺翔",
            lastAutomationStage = "DEPARTURE",
            lastRootPackage = "com.MobileTicket",
            lastEvidenceSource = "NONE",
            lastEvent = "页面包含账号 13800138000",
            lastError = "订单号 ABC123456789",
            accessibilityEventCount = 2
        )

        val report = buildDiagnosticReport(task, Instant.parse("2026-09-11T06:00:00Z"))
        assertTrue(report.contains("\"formatVersion\": \"9\""))
        assertTrue(report.contains("\"status\": \"FILLING_DEPARTURE\""))
        assertTrue(report.contains("\"automationMode\": \"任务信息自动查询（诊断）\""))
        assertTrue(report.contains("\"lastPageState\": \"HOME_PAGE\""))
        assertTrue(report.contains("\"accessibilityEventCount\": \"2\""))
        assertTrue(report.contains("\"passenger\": \"已配置（已脱敏）\""))
        assertFalse(report.contains(task.passengerName))
        assertFalse(report.contains("13800138000"))
        assertFalse(report.contains("ABC123456789"))
        assertTrue(report.contains("\"lastAction\": \"已记录（内容已脱敏）\""))
        assertTrue(report.contains("\"lastAutomationStage\": \"DEPARTURE\""))
        assertTrue(report.contains("\"lastRootPackage\": \"com.MobileTicket\""))
        assertTrue(report.contains("\"lastEvidenceSource\": \"NONE\""))
        assertTrue(report.contains("\"containsCredentials\": \"false\""))
        assertTrue(report.contains("\"containsPaymentData\": \"false\""))
    }

    @Test fun `report includes sanitized accessibility snapshots`() {
        val task = TicketTask(
            date = "2026-09-19",
            from = Station("汉口", "HKN"),
            to = Station("潜江", "QJN"),
            train = Train("D353", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
            seat = "二等座",
            passengerName = "蔡贺翔",
            saleDateTime = null
        )
        val snapshot = SanitizedAccessibilitySnapshot(
            capturedAt = 123L,
            source = "ACCESSIBILITY_EVENT",
            pageState = "DATE_PICKER",
            automationStage = "DATE",
            rootPackage = "com.MobileTicket",
            nodeCount = 3,
            truncated = false,
            treeJson = "{\"text\":\"选择乘车日期\",\"children\":[]}"
        )

        val report = buildDiagnosticReport(task, Instant.EPOCH, listOf(snapshot))
        assertTrue(report.contains("\"formatVersion\": \"9\""))
        assertTrue(report.contains("\"accessibilitySnapshots\": [{\"capturedAt\":123"))
        assertTrue(report.contains("\"pageState\":\"DATE_PICKER\""))
        assertTrue(report.contains("\"nodeCount\":3"))
        assertTrue(report.contains("\"tree\":{\"text\":\"选择乘车日期\",\"children\":[]}"))
        assertFalse(report.contains("蔡贺翔"))
    }
}
