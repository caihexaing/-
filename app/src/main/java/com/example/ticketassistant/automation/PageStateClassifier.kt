package com.example.ticketassistant.automation

enum class OfficialPageState {
    LAUNCHING, HOME_PAGE, SEARCH_FORM, STATION_PICKER, DATE_PICKER,
    SEARCH_RESULT_LOADING, SEARCH_RESULT_PARTIAL, SEARCH_RESULT, SEAT_SELECTION,
    PASSENGER_SELECTION, ORDER_CONFIRM, PENDING_PAYMENT,
    PROCESSING, SUBMIT_REJECTED, POPUP, UNKNOWN
}

internal fun detectOfficialPageState(text: String): OfficialPageState {
    val normalized = text.replace(Regex("\\s+"), "").lowercase()
    val hasTrainLikeToken = Regex("(?<![A-Za-z0-9])[gcdztksylpn]\\d{1,4}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        .containsMatchIn(normalized)
    val explicitSubmitFailure = listOf("提交失败", "订单失败", "无法提交", "重复订单", "已有未完成订单")
        .any(normalized::contains)
    val contextualSeatFailure = listOf("席位不足", "余票不足").any(normalized::contains) &&
        listOf("提交", "订单确认").any(normalized::contains)
    if (explicitSubmitFailure || contextualSeatFailure || listOf("网络异常", "网络错误").any(normalized::contains)) {
        return OfficialPageState.SUBMIT_REJECTED
    }
    if (listOf("订单确认", "提交订单").any(normalized::contains)) return OfficialPageState.ORDER_CONFIRM
    if (listOf("提交中", "正在提交", "排队中", "订单处理中", "处理中").any(normalized::contains)) {
        return OfficialPageState.PROCESSING
    }
    if (listOf("乘车人", "联系人").any(normalized::contains) &&
        listOf("确认", "下一步").any(normalized::contains)) return OfficialPageState.PASSENGER_SELECTION
    val homeNavigationCount = listOf("首页", "我的", "订单", "车票").count(normalized::contains)
    val hasHomeNavigation = homeNavigationCount >= 2 &&
        listOf("查询车票", "搜索车票", "出发地", "到达地", "乘车日期", "出发日期", "火车票")
            .any(normalized::contains)
    val hasTrainRowEvidence = hasTrainLikeToken && normalized.contains("车次") &&
        !listOf("正在加载", "加载中").any(normalized::contains)
    val hasStrongResultEvidence = listOf("筛选条件", "余票", "有票", "车次列表")
        .any(normalized::contains) || hasTrainRowEvidence
    val hasWeakResultEvidence = hasTrainLikeToken && normalized.contains("查询车票")
    val hasResultEvidence = hasStrongResultEvidence || (hasWeakResultEvidence && !hasHomeNavigation)
    val formFields = listOf("出发地", "出发站", "到达地", "到达站", "乘车日期", "出发日期")
        .count(normalized::contains)
    val hasSearchForm = formFields >= 2 && listOf("查询", "搜索车票", "查询车票").any(normalized::contains)
    if (listOf("选择出发站", "选择到达站", "站点列表", "热门站点", "车站选择").any(normalized::contains)) {
        return OfficialPageState.STATION_PICKER
    }
    if (listOf("日期选择", "选择日期", "日历").any(normalized::contains)) return OfficialPageState.DATE_PICKER
    if (listOf("查询中", "正在查询", "加载车次", "正在加载车次").any(normalized::contains)) {
        return OfficialPageState.SEARCH_RESULT_LOADING
    }
    // Home/form evidence must win over a stray train token from a hidden or
    // cached node; it is unsafe to treat the home screen as a result list.
    if (hasHomeNavigation && !hasResultEvidence) return OfficialPageState.HOME_PAGE
    if (hasSearchForm && !hasResultEvidence) return OfficialPageState.SEARCH_FORM
    if ((hasTrainLikeToken || normalized.contains("车次")) && !hasResultEvidence) {
        return OfficialPageState.SEARCH_RESULT_PARTIAL
    }
    if (hasResultEvidence) return OfficialPageState.SEARCH_RESULT
    val hasSeatPickerEvidence = listOf("选择席别", "席别选择", "选择座席", "席位选择").any(normalized::contains) ||
        (listOf("席别", "商务座", "一等座", "二等座", "硬卧", "软卧").any(normalized::contains) &&
            listOf("预订", "下一步").any(normalized::contains))
    if (hasSeatPickerEvidence) return OfficialPageState.SEAT_SELECTION
    if (listOf("公告", "活动", "优惠", "温馨提示").any(normalized::contains) &&
        listOf("关闭", "知道了", "暂不").any(normalized::contains)) return OfficialPageState.POPUP
    if (listOf("启动", "加载", "请稍候", "正在加载").any(normalized::contains)) return OfficialPageState.LAUNCHING
    return OfficialPageState.UNKNOWN
}

/** Broad diagnostic signal only; it is never sufficient to confirm an order. */
internal fun hasPendingPaymentCandidate(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), "").lowercase()
    return listOf("待支付", "待付款", "订单待支付", "未支付订单", "支付倒计时").any(normalized::contains)
}
