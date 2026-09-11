package com.example.ticketassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SaleStateRulesTest {
    private val now = Instant.parse("2026-09-11T00:00:00Z")

    @Test fun `already on sale does not require sale date`() {
        assertNull(SaleStateRules.validateForSave(SaleState.ALREADY_ON_SALE, null, now))
    }

    @Test fun `not yet on sale requires a future complete date`() {
        assertEquals(
            "已确认尚未开售，请填写完整开售日期时间 yyyy-MM-dd HH:mm",
            SaleStateRules.validateForSave(SaleState.NOT_YET_ON_SALE, null, now)
        )
        assertEquals(
            "开售日期时间必须晚于当前时间，请重新查询确认开售状态",
            SaleStateRules.validateForSave(SaleState.NOT_YET_ON_SALE, "2026-09-10 14:00", now)
        )
        assertNull(SaleStateRules.validateForSave(SaleState.NOT_YET_ON_SALE, "2026-09-12 14:00", now))
    }

    @Test fun `unknown state cannot be enabled`() {
        assertEquals(
            "无法确认开售状态，请重新查询后再启用任务",
            SaleStateRules.validateForSave(SaleState.UNKNOWN, null, now)
        )
    }

    @Test fun `manual not yet confirmation only applies to a found target`() {
        assertEquals(
            SaleState.NOT_YET_ON_SALE,
            SaleStateRules.resolveState(SaleAssessment(SaleState.UNKNOWN, targetFound = true), true)
        )
        assertEquals(
            SaleState.UNKNOWN,
            SaleStateRules.resolveState(SaleAssessment(SaleState.UNKNOWN, targetFound = false), true)
        )
    }
}
