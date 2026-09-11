package com.example.ticketassistant.automation

import com.example.ticketassistant.data.Station
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.Train
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPaymentVerificationTest {
    private val task = TicketTask(
        taskId = "task-1", date = "2026-09-19", from = Station("汉口", "HKN"), to = Station("潜江", "QJN"),
        train = Train("D5963", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
        seat = "二等座", passengerName = "蔡贺翔", saleDateTime = "2026-09-10 14:00"
    )

    @Test fun `payment label alone is not a confirmed order`() {
        assertFalse(isVerifiedPendingPaymentPage("订单中心 待支付", task))
    }

    @Test fun `all target fields and price confirm pending payment`() {
        assertTrue(isVerifiedPendingPaymentPage("订单号 1234567890 2026-09-19 D5963 二等座 蔡贺翔 待支付 ￥45.00", task))
    }

    @Test fun `missing order number or travel date is not confirmed`() {
        assertFalse(isVerifiedPendingPaymentPage("2026-09-19 D5963 二等座 蔡贺翔 待支付 ￥45.00", task))
        assertFalse(isVerifiedPendingPaymentPage("订单号 1234567890 D5963 二等座 蔡贺翔 待支付 ￥45.00", task))
    }

    @Test fun `confirmation requires submit result stage and official root`() {
        val evidence = "订单号 1234567890 2026-09-19 D5963 二等座 蔡贺翔 待支付 ￥45.00"
        assertTrue(isPendingPaymentConfirmationAllowed("WAITING_RESULT", "com.MobileTicket", evidence, task))
        assertFalse(isPendingPaymentConfirmationAllowed("ORDER", "com.MobileTicket", evidence, task))
        assertFalse(isPendingPaymentConfirmationAllowed("WAITING_RESULT", "android", evidence, task))
        assertFalse(isPendingPaymentConfirmationAllowed("WAITING_RESULT", "com.MobileTicket", "订单中心 待支付", task))
    }
}
