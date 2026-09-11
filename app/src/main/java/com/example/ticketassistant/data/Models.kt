package com.example.ticketassistant.data

data class Station(val name: String, val telecode: String, val city: String = "", val pinyin: String = "")

data class Train(
    val trainNo: String,
    val from: String,
    val to: String,
    val depart: String,
    val arrive: String,
    val duration: String,
    val seats: Map<String, String>,
    val saleTime: String? = null
)

data class TicketTask(
    val taskId: String = java.util.UUID.randomUUID().toString(),
    val date: String,
    val from: Station,
    val to: Station,
    val train: Train,
    val seat: String,
    val passengerName: String,
    val saleState: SaleState = SaleState.UNKNOWN,
    val saleDateTime: String?,
    val saleTimeSource: SaleTimeSource = SaleTimeSource.UNKNOWN,
    val maxRunMinutes: Int = 120,
    val enabled: Boolean = true,
    val status: TaskStatus = TaskStatus.ENABLED,
    val lastEvent: String? = null,
    val lastEventAt: Long? = null,
    val lastError: String? = null,
    val lastPageState: String? = null,
    val lastAction: String? = null,
    val lastAccessibilityEventAt: Long? = null,
    val accessibilityEventCount: Int = 0
)

enum class TaskStatus {
    DRAFT,
    ENABLED,
    WAITING_FOR_SALE,
    PREPARING,
    WAITING_OFFICIAL_PAGE,
    VALIDATING_SEARCH_RESULT,
    SELECTING_TRAIN_SEAT,
    SELECTING_PASSENGER,
    VALIDATING_ORDER,
    SUBMIT_ACTION_SENT,
    WAITING_SERVER_RESULT,
    OBSERVING,
    SEARCHING,
    SUBMIT_REJECTED,
    TAKEOVER,
    PENDING_PAYMENT,
    RESULT_UNKNOWN,
    EXPIRED,
    DISABLED
}

enum class SaleTimeSource { OFFICIAL, USER_CONFIRMED, UNKNOWN }

enum class SaleState {
    ALREADY_ON_SALE,
    NOT_YET_ON_SALE,
    UNKNOWN
}

const val TASK_ZONE_ID = "Asia/Shanghai"
