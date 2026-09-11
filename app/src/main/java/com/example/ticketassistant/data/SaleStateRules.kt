package com.example.ticketassistant.data

import java.time.Instant

object SaleStateRules {
    fun resolveState(assessment: SaleAssessment, confirmNotYetOnSale: Boolean): SaleState = when {
        assessment.state == SaleState.ALREADY_ON_SALE -> SaleState.ALREADY_ON_SALE
        confirmNotYetOnSale && assessment.targetFound -> SaleState.NOT_YET_ON_SALE
        else -> SaleState.UNKNOWN
    }

    fun validateForSave(state: SaleState, saleDateTime: String?, now: Instant = Instant.now()): String? = when (state) {
        SaleState.ALREADY_ON_SALE -> null
        SaleState.NOT_YET_ON_SALE -> when {
            saleDateTime.isNullOrBlank() -> "已确认尚未开售，请填写完整开售日期时间 yyyy-MM-dd HH:mm"
            TaskTiming.parseSaleDateTime(saleDateTime) == null -> "开售日期时间格式应为 yyyy-MM-dd HH:mm"
            !TaskTiming.isFutureSaleDateTime(saleDateTime, now) -> "开售日期时间必须晚于当前时间，请重新查询确认开售状态"
            else -> null
        }
        SaleState.UNKNOWN -> "无法确认开售状态，请重新查询后再启用任务"
    }
}
