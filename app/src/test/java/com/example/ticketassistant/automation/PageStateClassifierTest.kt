package com.example.ticketassistant.automation

import com.example.ticketassistant.data.Station
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.Train
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `homepage content with advertisement and notice stays home page`() {
        assertEquals(
            OfficialPageState.HOME_PAGE,
            detectOfficialPageState("首页 我的 订单 车票 汉口 潜江 9月19日 查询车票 活动广告 公告 关闭")
        )
    }

    @Test fun `pending payment keyword does not override homepage or become a page state`() {
        assertEquals(
            OfficialPageState.HOME_PAGE,
            detectOfficialPageState("首页 我的 订单 车票 出发地 到达地 查询车票 待支付")
        )
        assertEquals(OfficialPageState.UNKNOWN, detectOfficialPageState("订单中心 待支付"))
        assertTrue(hasPendingPaymentCandidate("首页菜单 待支付"))
    }

    @Test fun `stage pages win over bottom navigation labels`() {
        assertEquals(OfficialPageState.SEAT_SELECTION, detectOfficialPageState("首页 我的 订单 车票 席别 二等座 预订"))
        assertEquals(OfficialPageState.PASSENGER_SELECTION, detectOfficialPageState("首页 订单 乘车人 蔡贺翔 下一步"))
    }

    @Test fun `result list with seat columns and booking action stays search result`() {
        assertEquals(
            OfficialPageState.SEARCH_RESULT,
            detectOfficialPageState("首页 我的 订单 车票 查询车票 2026年9月19日 出发地 汉口 到达地 潜江 车次 D5995 二等座 预订")
        )
    }

    @Test fun `home search form is not treated as results`() {
        assertEquals(OfficialPageState.HOME_PAGE, detectOfficialPageState("首页 我的 订单 车票 查询车票 出发地 到达地"))
        assertEquals(OfficialPageState.HOME_PAGE, detectOfficialPageState("首页 我的 订单 车票 查询车票 2026-09-19 出发地 到达地"))
        assertEquals(OfficialPageState.SEARCH_FORM, detectOfficialPageState("出发地 到达地 乘车日期 查询"))
    }

    @Test fun `home evidence wins over stray cached train text`() {
        assertEquals(
            OfficialPageState.HOME_PAGE,
            detectOfficialPageState("首页 我的 订单 车票 出发地 到达地 查询车票 D353 待支付")
        )
    }

    @Test fun `explicit picker and loading states are distinct`() {
        assertEquals(OfficialPageState.STATION_PICKER, detectOfficialPageState("选择出发站 站点列表 汉口 潜江"))
        assertEquals(OfficialPageState.DATE_PICKER, detectOfficialPageState("日期选择 日历 9月19日"))
        assertEquals(OfficialPageState.SEARCH_RESULT_LOADING, detectOfficialPageState("正在查询 加载车次"))
        assertEquals(OfficialPageState.SEARCH_RESULT_PARTIAL, detectOfficialPageState("车次 D353 正在加载"))
    }

    @Test fun `search results require result evidence rather than dates alone`() {
        assertEquals(OfficialPageState.SEARCH_RESULT, detectOfficialPageState("首页 查询车票 D5963 余票 二等座"))
    }

    @Test fun `processing and rejection pages are classified`() {
        assertEquals(OfficialPageState.PROCESSING, detectOfficialPageState("订单处理中 请稍候"))
        assertEquals(OfficialPageState.SUBMIT_REJECTED, detectOfficialPageState("提交失败 席位不足"))
        assertEquals(OfficialPageState.SEARCH_RESULT, detectOfficialPageState("查询车票 D353 汉口 潜江 余票不足"))
        assertEquals(OfficialPageState.SUBMIT_REJECTED, detectOfficialPageState("订单提交失败：余票不足"))
        assertEquals(OfficialPageState.SUBMIT_REJECTED, detectOfficialPageState("订单确认 提交订单 余票不足"))
    }

    @Test fun `search context requires matching travel date and route`() {
        val task = TicketTask(
            date = "2026-09-19",
            from = Station("汉口", "HKN"),
            to = Station("潜江", "QJN"),
            train = Train("D353", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
            seat = "二等座",
            passengerName = "乘客",
            saleDateTime = null
        )
        assertTrue(matchesSearchContext("查询车票 2026-09-19 汉口 潜江 D353 二等座 余票", task))
        assertTrue(matchesSearchContext("查询车票 2026年9月19日 汉口 潜江 D353 二等座 余票", task))
        assertFalse(matchesSearchContext("查询车票 2026-09-18 汉口 潜江 D353 二等座 余票", task))
        assertFalse(matchesSearchContext("查询车票 2026-09-19 汉口 武昌 D353 二等座 余票", task))
    }

    @Test fun `search context distinguishes missing and conflicting evidence`() {
        val task = TicketTask(
            date = "2026-09-19",
            from = Station("汉口", "HKN"),
            to = Station("潜江", "QJN"),
            train = Train("D353", "汉口", "潜江", "07:25", "08:16", "00:51", emptyMap()),
            seat = "二等座",
            passengerName = "乘客",
            saleDateTime = null
        )
        assertEquals(SearchContextStatus.MATCH, classifySearchContext("查询车票 2026-09-19 汉口 潜江 D353 二等座 余票", task).status)
        assertEquals(SearchContextStatus.MISSING, classifySearchContext("查询车票 余票", task).status)
        assertEquals(SearchContextStatus.CONFLICT, classifySearchContext("查询车票 2026-09-18 汉口 潜江 D353 二等座 余票", task).status)
        assertEquals(SearchContextStatus.WRONG_PAGE, classifySearchContext("首页 出发地 到达地 查询车票", task).status)
    }
}
