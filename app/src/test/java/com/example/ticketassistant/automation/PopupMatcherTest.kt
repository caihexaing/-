package com.example.ticketassistant.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupMatcherTest {
    @Test fun `ordinary popup text is recognized`() {
        assertTrue(PopupMatcher.isOrdinaryPopupText("活动公告 温馨提示 知道了"))
        assertTrue(PopupMatcher.isOrdinaryPopupText("优惠提示 关闭"))
    }

    @Test fun `homepage banner text is not enough to close`() {
        assertFalse(PopupMatcher.isOrdinaryPopupText("首页 活动广告 公告 查询车票 汉口 潜江"))
    }

    @Test fun `sensitive prompts are never auto dismissed`() {
        assertTrue(PopupMatcher.containsSensitivePrompt("活动公告 请完成验证码 关闭"))
        assertTrue(PopupMatcher.containsSensitivePrompt("请登录后继续"))
        assertFalse(PopupMatcher.containsSensitivePrompt("活动公告 温馨提示 知道了"))
    }
}
