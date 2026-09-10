package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakeoverReasonTest {
    @Test fun `ordinary logged in navigation text does not trigger takeover`() {
        assertNull(takeoverReason("登录管理  切换账号  个人中心"))
    }

    @Test fun `explicit login prompt triggers login takeover`() {
        assertEquals("官方 12306 需要登录，请手动完成", takeoverReason("请先登录后继续"))
    }

    @Test fun `captcha prompt takes priority`() {
        assertEquals("官方 12306 要求验证码，请手动完成", takeoverReason("请完成图形验证码"))
    }

    @Test fun `payment prompt triggers manual takeover`() {
        assertEquals("已进入订单确认、候补或支付环节，请手动接管", takeoverReason("订单确认"))
    }

    @Test fun `identity labels without an action prompt do not trigger takeover`() {
        assertNull(takeoverReason("乘车人实名认证信息  身份核验记录"))
    }

    @Test fun `explicit identity prompt triggers identity takeover`() {
        assertEquals("官方 12306 需要身份核验，请手动完成", takeoverReason("请完成身份核验"))
    }
}
