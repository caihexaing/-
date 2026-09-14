package com.example.ticketassistant.automation

/** Keeps persisted timeline fields tied to actual dispatched actions. */
internal fun isTrainActionDispatched(action: String): Boolean =
    action.contains("目标车次卡片点击已派发") ||
        action.contains("目标车次摘要按钮点击已派发")

internal fun isSeatActionDispatched(action: String): Boolean =
    action.contains("目标席别行“预订”控件点击已派发")

internal fun isTargetControlDispatched(action: String): Boolean =
    isTrainActionDispatched(action) || isSeatActionDispatched(action)

internal fun isBookingActionDispatched(action: String): Boolean =
    action.contains("目标席别行“预订”控件点击已派发")

internal fun isPassengerActionDispatched(action: String): Boolean =
    action.contains("目标乘车人点击已派发") ||
        action.contains("乘车人页面继续控件点击已派发")

internal fun isSubmitActionDispatched(action: String): Boolean =
    action.contains("提交订单点击已派发")
