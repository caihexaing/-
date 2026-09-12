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
    val lastAutomationStage: String? = null,
    val lastRootPackage: String? = null,
    val lastEvidenceSource: String? = null,
    val lastSearchContextStatus: String? = null,
    val lastMissingEvidence: String? = null,
    val lastSearchContextAt: Long? = null,
    val lastSearchSnapshotFingerprint: String? = null,
    val lastSaleT0At: Long? = null,
    val coldStartAt: Long? = null,
    val coldStartAttempts: Int = 0,
    val coldStartLastFailure: String? = null,
    val lastWindowChangedAt: Long? = null,
    val lastAccessibilityEventAt: Long? = null,
    val accessibilityEventCount: Int = 0,
    val automationRunId: String? = null,
    val stageEnteredAt: Long? = null,
    val lastActionAt: Long? = null,
    val lastActionOutcome: String? = null,
    val lastResultPageAt: Long? = null,
    val lastTargetControlAt: Long? = null,
    val lastBookingActionAt: Long? = null,
    val lastPassengerActionAt: Long? = null,
    val lastSubmitAt: Long? = null,
    val lastOrderEvidenceAt: Long? = null
)

enum class TaskStatus {
    DRAFT,
    ENABLED,
    WAITING_FOR_SALE,
    PREPARING,
    SALE_T0,
    COLD_START,
    OPENING_OFFICIAL_APP,
    OPENING_HOME,
    FILLING_SEARCH_FORM,
    WAITING_SEARCH_RESULT,
    WAITING_OFFICIAL_PAGE,
    OPENING_SEARCH,
    FILLING_DEPARTURE,
    FILLING_ARRIVAL,
    FILLING_DATE,
    SUBMITTING_SEARCH,
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

internal fun TaskStatus.isAccessibilityActive(): Boolean = this in setOf(
    TaskStatus.PREPARING,
    TaskStatus.SALE_T0,
    TaskStatus.COLD_START,
    TaskStatus.OPENING_OFFICIAL_APP,
    TaskStatus.OPENING_HOME,
    TaskStatus.FILLING_SEARCH_FORM,
    TaskStatus.WAITING_SEARCH_RESULT,
    TaskStatus.WAITING_OFFICIAL_PAGE,
    TaskStatus.OPENING_SEARCH,
    TaskStatus.FILLING_DEPARTURE,
    TaskStatus.FILLING_ARRIVAL,
    TaskStatus.FILLING_DATE,
    TaskStatus.SUBMITTING_SEARCH,
    TaskStatus.VALIDATING_SEARCH_RESULT,
    TaskStatus.SELECTING_TRAIN_SEAT,
    TaskStatus.SELECTING_PASSENGER,
    TaskStatus.VALIDATING_ORDER,
    TaskStatus.SUBMIT_ACTION_SENT,
    TaskStatus.WAITING_SERVER_RESULT,
    TaskStatus.OBSERVING,
    TaskStatus.SEARCHING
)

internal fun taskSnapshotKey(task: TicketTask): String = listOf(
    task.taskId,
    task.date,
    task.from.name,
    task.to.name,
    task.train.trainNo,
    task.train.depart,
    task.train.arrive,
    task.seat
).joinToString("|")
