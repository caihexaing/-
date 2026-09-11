package com.example.ticketassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class PageStateClassifierTest {
    @Test fun `official order stages are classified`() {
        assertEquals(OfficialPageState.SEARCH_RESULT, detectOfficialPageState("查询车票 D5963 出发地 汉口 到达地 潜江"))
        assertEquals(OfficialPageState.SEAT_SELECTION, detectOfficialPageState("席别 二等座 预订"))
        assertEquals(OfficialPageState.PASSENGER_SELECTION, detectOfficialPageState("乘车人 蔡贺翔 下一步"))
        assertEquals(OfficialPageState.ORDER_CONFIRM, detectOfficialPageState("订单确认 提交订单"))
    }

    @Test fun `popup and unknown pages require takeover`() {
        assertEquals(OfficialPageState.POPUP, detectOfficialPageState("活动公告 温馨提示 关闭"))
        assertEquals(OfficialPageState.UNKNOWN, detectOfficialPageState("个人中心 设置"))
    }

    @Test fun `stage pages win over bottom navigation labels`() {
        assertEquals(OfficialPageState.SEAT_SELECTION, detectOfficialPageState("首页 我的 订单 车票 席别 二等座 预订"))
        assertEquals(OfficialPageState.PASSENGER_SELECTION, detectOfficialPageState("首页 订单 乘车人 蔡贺翔 下一步"))
    }

    @Test fun `home search form is not treated as results`() {
        assertEquals(OfficialPageState.HOME_PAGE, detectOfficialPageState("首页 我的 订单 车票 查询车票 出发地 到达地"))
        assertEquals(OfficialPageState.HOME_PAGE, detectOfficialPageState("首页 我的 订单 车票 查询车票 2026-09-19 出发地 到达地"))
    }

    @Test fun `search results require result evidence rather than dates alone`() {
        assertEquals(OfficialPageState.SEARCH_RESULT, detectOfficialPageState("首页 查询车票 D5963 余票 二等座"))
    }
}
