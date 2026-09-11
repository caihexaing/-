package com.example.ticketassistant.automation

enum class OfficialPageState {
    LAUNCHING, HOME_PAGE, SEARCH_RESULT, SEAT_SELECTION,
    PASSENGER_SELECTION, ORDER_CONFIRM, PENDING_PAYMENT, POPUP, UNKNOWN
}

internal fun detectOfficialPageState(text: String): OfficialPageState {
    val normalized = text.replace(" ", "").lowercase()
    if (listOf("公告", "活动", "优惠", "温馨提示").any(normalized::contains) &&
        listOf("关闭", "知道了", "暂不").any(normalized::contains)) return OfficialPageState.POPUP
    if (listOf("订单确认", "提交订单").any(normalized::contains)) return OfficialPageState.ORDER_CONFIRM
    if (isPendingPaymentPage(text)) return OfficialPageState.PENDING_PAYMENT
    if (listOf("查询车票", "出发地", "到达地", "筛选条件").any(normalized::contains)) return OfficialPageState.SEARCH_RESULT
    if (listOf("席别", "商务座", "一等座", "二等座", "硬卧", "软卧").any(normalized::contains) &&
        listOf("预订", "下一步").any(normalized::contains)) return OfficialPageState.SEAT_SELECTION
    if (listOf("乘车人", "联系人").any(normalized::contains) &&
        listOf("确认", "下一步").any(normalized::contains)) return OfficialPageState.PASSENGER_SELECTION
    if (listOf("首页", "我的", "订单", "车票").any(normalized::contains) &&
        !normalized.contains("查询车票")) return OfficialPageState.HOME_PAGE
    if (listOf("启动", "加载", "请稍候", "正在加载").any(normalized::contains)) return OfficialPageState.LAUNCHING
    return OfficialPageState.UNKNOWN
}
