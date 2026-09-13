package com.example.ticketassistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTreeSnapshotTest {
    @Test fun `diagnostic text redacts personal authentication order and payment values`() {
        val input = "姓名：蔡贺翔 手机 13800138000 身份证号 11010519491231002X 订单号 ABC12345678 金额：51.50元"
        val sanitized = sanitizeAccessibilityTextForDiagnostics(input, "蔡贺翔", password = false)

        assertEquals(
            "姓名:<PASSENGER> 手机 <PHONE> 身份证号:<REDACTED> 订单号:<REDACTED> 金额:<AMOUNT>",
            sanitized
        )
        assertTrue(!sanitized.contains("蔡贺翔"))
        assertTrue(!sanitized.contains("13800138000"))
        assertTrue(!sanitized.contains("11010519491231002X"))
        assertTrue(!sanitized.contains("ABC12345678"))
        assertTrue(!sanitized.contains("51.50"))
    }

    @Test fun `password nodes are fully redacted`() {
        assertEquals(
            "<REDACTED>",
            sanitizeAccessibilityTextForDiagnostics("支付密码 123456", "", password = true)
        )
    }

    @Test fun `snapshot buffer is bounded and clearable`() {
        AccessibilitySnapshotBuffer.clear()
        repeat(7) { index ->
            AccessibilitySnapshotBuffer.add(
                SanitizedAccessibilitySnapshot(
                    capturedAt = index.toLong(),
                    source = "TEST",
                    pageState = "UNKNOWN",
                    automationStage = "DATE",
                    rootPackage = "com.MobileTicket",
                    nodeCount = 1,
                    truncated = false,
                    treeJson = "{}"
                )
            )
        }
        val snapshots = AccessibilitySnapshotBuffer.list()
        assertEquals(5, snapshots.size)
        assertEquals(2L, snapshots.first().capturedAt)
        AccessibilitySnapshotBuffer.clear()
        assertTrue(AccessibilitySnapshotBuffer.list().isEmpty())
    }
}
