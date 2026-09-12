package com.example.ticketassistant.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.taskSnapshotKey

/**
 * 仅辅助官方 12306 的公开页面操作。验证码、登录、身份核验、风控和未知页面永远交给用户。
 * 页面入口和每个动作都必须先被诊断记录；无法确认时停止，而不是静默等待或盲点。
 */
class TicketAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val staticRefresh = object : Runnable {
        override fun run() {
            val task = TaskStore(this@TicketAccessibilityService).load()
            if (task?.enabled == true && task.status in STATIC_REFRESH_STATUSES &&
                (task.status in COLD_START_STATUSES || task.status == TaskStatus.SALE_T0 || lastOfficialRootAt > 0L)) {
                processCurrentWindow("STATIC_REFRESH")
            }
            mainHandler.postDelayed(this, STATIC_REFRESH_INTERVAL_MS)
        }
    }
    private var lastActionAt = 0L
    private var activeTaskKey: String? = null
    private var activeAutomationRunId: String? = null
    private var stage = Stage.WAITING_PAGE
    private var pageState = OfficialPageState.LAUNCHING
    private var emptyTreeEvents = 0
    private var resultEvents = 0
    private var pendingEvidence = ""
    private var pendingEvidenceStartedAt = 0L
    private var pendingEvidenceEvents = 0
    private var formStartedAt = 0L
    private var formWaitEvents = 0
    private val formEvidence = mutableSetOf<SearchField>()
    private var navigationAttempted = false
    private var searchActionSent = false
    private var popupDismissSent = false
    private var fastPathWaitEvents = 0
    private var expectedPageWaitEvents = 0
    private var coldStartWaitEvents = 0
    private var lastObservedRootPackage: String? = null
    private var seatSelected = false
    private var seatBeforeClickFingerprint: String? = null
    private var passengerSelected = false
    private var passengerBeforeClickFingerprint: String? = null
    private var actionWaitEvents = 0
    private var lastDiagnosticKey: String? = null
    private var lastDiagnosticAt = 0L
    private var lastProgressRecordAt = 0L
    private val searchInteractor by lazy {
        OfficialSearchInteractor { action -> recordDiagnostic(pageState, action) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        mainHandler.removeCallbacks(staticRefresh)
        mainHandler.post(staticRefresh)
    }

    private fun activateSaleT0(taskId: String) {
        val task = TaskStore(this).load()
        if (task?.taskId == taskId && task.status in COLD_START_STATUSES) {
            processCurrentWindow("SALE_T0")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()
        val task = TaskStore(this).load()
        if (eventPackage == OFFICIAL_PACKAGE || task?.status in ACTIVE_STATUSES) {
            processCurrentWindow(if (eventPackage == OFFICIAL_PACKAGE) "ACCESSIBILITY_EVENT" else "WINDOW_CHANGE")
        }
    }

    private fun processCurrentWindow(source: String) {
        val task = TaskStore(this).load() ?: return
        if (!task.enabled || task.status !in ACTIVE_STATUSES) return

        if (stage == Stage.DONE && task.status in ACTIVE_STATUSES) {
            // A manual retry reuses the same task configuration but must start
            // a fresh in-memory flow instead of remaining in the terminal stage.
            activeTaskKey = null
        }
        val key = taskSnapshotKey(task)
        val runChanged = task.automationRunId != activeAutomationRunId
        if (activeTaskKey != key || runChanged) {
            activeTaskKey = key
            activeAutomationRunId = task.automationRunId
            stage = stageFor(task.status)
            pageState = OfficialPageState.LAUNCHING
            lastActionAt = 0L
            emptyTreeEvents = 0
            resultEvents = 0
            pendingEvidence = ""
            pendingEvidenceStartedAt = 0L
            pendingEvidenceEvents = 0
            formStartedAt = System.currentTimeMillis()
            formWaitEvents = 0
            formEvidence.clear()
            navigationAttempted = false
            searchActionSent = false
            popupDismissSent = false
            fastPathWaitEvents = 0
            expectedPageWaitEvents = 0
            coldStartWaitEvents = 0
            lastObservedRootPackage = null
            seatSelected = false
            seatBeforeClickFingerprint = null
            passengerSelected = false
            passengerBeforeClickFingerprint = null
            actionWaitEvents = 0
            lastDiagnosticKey = null
            lastDiagnosticAt = 0L
            lastProgressRecordAt = 0L
            searchInteractor.reset()
        }
        if (task.status in COLD_START_STATUSES && stage in setOf(Stage.WAITING_PAGE, Stage.PREWARM, Stage.SALE_T0)) {
            stage = stageFor(task.status)
            fastPathWaitEvents = 0
            expectedPageWaitEvents = 0
            recordDiagnostic(OfficialPageState.LAUNCHING, "收到 SALE_T0 信号，进入官方 App 冷启动导航", evidenceSource = "SALE_T0_SIGNAL")
        }

        val gate = PersistentSubmitGate(getSharedPreferences("submit_gate", MODE_PRIVATE), submitGateKey(task))
        if (gate.isLocked() && stage !in setOf(Stage.WAITING_RESULT, Stage.DONE)) {
            stage = Stage.WAITING_RESULT
            TaskStore(this).updateStatus(
                TaskStatus.WAITING_SERVER_RESULT,
                "提交锁已存在，等待官方 12306 结果；不会自动重复提交"
            )
        }

        val root = rootInActiveWindow
        if (root == null) {
            handleEmptyTree("未读取到官方 12306 页面节点")
            return
        }
        val rootPackage = root.packageName?.toString()
        if (rootPackage != lastObservedRootPackage) {
            lastObservedRootPackage = rootPackage
            TaskStore(this).recordWindowChange(rootPackage, "检测到前台窗口变化")
        }
        if (!isOfficialRootPackage(root.packageName)) {
            recordDiagnostic(
                OfficialPageState.UNKNOWN,
                "当前前台不是官方 12306（来源=$source），已停止自动操作",
                rootPackage = rootPackage ?: "UNKNOWN",
                evidenceSource = "ROOT_PACKAGE_MISMATCH"
            )
            if (isColdStartStage(stage)) {
                waitForColdStart("官方 12306 尚未出现在前台（当前窗口 ${rootPackage ?: "未知"}）")
            } else if (task.status != TaskStatus.PREPARING) {
                TaskStore(this).clearSearchSnapshot()
                takeover("官方 12306 不在前台，当前窗口为 ${rootPackage ?: "未知"}")
            }
            return
        }
        lastOfficialRootAt = System.currentTimeMillis()
        val text = root.textContent()
        if (text.isBlank()) {
            handleEmptyTree("官方 12306 页面节点为空（来源=$source）")
            return
        }
        emptyTreeEvents = 0

        takeoverReason(text)?.let { reason ->
            recordDiagnostic(OfficialPageState.UNKNOWN, reason, rootPackage = rootPackage, evidenceSource = "TAKEOVER_REASON")
            takeover(reason)
            return
        }

        val popupMatch = PopupMatcher.find(root)
        pageState = when (popupMatch) {
            is PopupMatcher.Result.Unique, is PopupMatcher.Result.Ambiguous -> OfficialPageState.POPUP
            PopupMatcher.Result.None -> detectOfficialPageState(text)
        }
        val pendingCandidate = hasPendingPaymentCandidate(text)
        val contextCheck = if (pageState == OfficialPageState.SEARCH_RESULT || pageState == OfficialPageState.SEARCH_RESULT_PARTIAL) {
            classifySearchContext(text, task)
        } else null
        recordDiagnostic(
            pageState,
            rootPackage = rootPackage,
            evidenceSource = when {
                stage == Stage.WAITING_RESULT && isPendingPaymentPage(text) -> "PENDING_PAYMENT_STRONG_CANDIDATE"
                pendingCandidate -> "PENDING_PAYMENT_KEYWORD_ONLY"
                else -> "NONE"
            },
            contextStatus = contextCheck?.status?.name,
            missingEvidence = contextCheck?.missing?.joinToString("、"),
            snapshotFingerprint = contextCheck?.let { searchSnapshotFingerprint(text) }
        )
        if (pageState == OfficialPageState.POPUP) {
            when (popupMatch) {
                is PopupMatcher.Result.Unique -> {
                    if (popupDismissSent) {
                        if (System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) return
                        takeover("普通弹窗关闭动作已派发，但页面仍未恢复；请手动核对")
                        return
                    }
                    val clicked = clickNodeOrParent(popupMatch.node)
                    recordDiagnostic(pageState, "普通弹窗关闭${if (clicked) "动作已派发" else "动作未派发"}（${popupMatch.label}）")
                    if (!clicked) {
                        takeover("普通弹窗关闭控件无法操作，请手动关闭")
                    } else {
                        popupDismissSent = true
                        lastActionAt = System.currentTimeMillis()
                        TaskStore(this).updateStatus(TaskStatus.WAITING_OFFICIAL_PAGE, "已关闭普通弹窗，等待官方 12306 页面")
                    }
                }
                is PopupMatcher.Result.Ambiguous -> takeover("普通弹窗关闭控件不唯一（${popupMatch.count} 个），请手动关闭")
                PopupMatcher.Result.None -> takeover("检测到疑似弹窗但无法安全定位关闭控件，请手动核对")
            }
            return
        }
        popupDismissSent = false
        if (pageState == OfficialPageState.SUBMIT_REJECTED) {
            if (stage == Stage.WAITING_RESULT) {
                submitRejected("官方 12306 未创建订单：${failureCategory(text)}")
            } else {
                takeover("官方 12306 页面显示错误或不可继续，尚未提交订单；请手动核对")
            }
            return
        }

        if (stage == Stage.WAITING_RESULT) {
            handleSubmitResult(text, task, rootPackage)
            return
        }
        if (stage != Stage.DONE && System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) return
        if (stage == Stage.SALE_T0) {
            when (pageState) {
                OfficialPageState.SEARCH_RESULT -> when (contextCheck?.status) {
                    SearchContextStatus.MATCH -> {
                        fastPathWaitEvents = 0
                        expectedPageWaitEvents = 0
                        stage = Stage.VALIDATING_SEARCH
                        formWaitEvents = 0
                        TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "开售 T0 已确认任务一致结果页，正在定位目标车次")
                    }
                    SearchContextStatus.MISSING -> {
                        waitForSearchContext(contextCheck?.missing.orEmpty())
                        return
                    }
                    SearchContextStatus.CONFLICT, SearchContextStatus.WRONG_PAGE -> {
                        takeover("开售 T0 结果页与任务冲突，未点击首页入口或返回按钮")
                        return
                    }
                    null -> return
                }
                OfficialPageState.SEARCH_RESULT_LOADING, OfficialPageState.SEARCH_RESULT_PARTIAL -> {
                    fastPathWaitEvents++
                    recordDiagnostic(pageState, "开售 T0 等待结果页证据补齐（第 ${fastPathWaitEvents} 次）", evidenceSource = "SALE_FAST_PATH_WAIT")
                    if (fastPathWaitEvents >= MAX_FAST_PATH_WAIT_EVENTS) {
                        takeover("开售 T0 结果页证据不足，未执行冷启动查询")
                    }
                    return
                }
                OfficialPageState.HOME_PAGE, OfficialPageState.SEARCH_FORM -> {
                    handleColdStartPage(root, text, task)
                    return
                }
                else -> {
                    waitForColdStart("开售 T0 尚未读取到官方首页或查询表单")
                    return
                }
            }
        }
        if (isColdStartStage(stage)) {
            when (pageState) {
                OfficialPageState.HOME_PAGE, OfficialPageState.SEARCH_FORM -> {
                    handleColdStartPage(root, text, task)
                }
                OfficialPageState.SEARCH_RESULT -> {
                    stage = Stage.VALIDATING_SEARCH
                    TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "已发现官方查询结果页，正在核对任务上下文")
                    validateAndSelectTrain(root, text, task)
                }
                OfficialPageState.SEARCH_RESULT_LOADING, OfficialPageState.SEARCH_RESULT_PARTIAL,
                OfficialPageState.LAUNCHING, OfficialPageState.UNKNOWN -> waitForColdStart("等待官方首页或车票查询页面")
                else -> takeover("冷启动时官方页面状态未知（${pageState.name}），已停止自动操作")
            }
            // The handler may advance several form stages in one tree read.
            // Stop here so the same accessibility event cannot repeat the
            // station/date/action that was just dispatched.
            return
        }
        if (pageState == OfficialPageState.PROCESSING) {
            takeover("官方 12306 正在处理，但任务尚未发送提交点击，请手动确认页面")
            return
        }

        if (stage == Stage.SEAT && pageState == OfficialPageState.PASSENGER_SELECTION) {
            stage = Stage.PASSENGER
            TaskStore(this).updateStatus(TaskStatus.SELECTING_PASSENGER, "车次已预订，正在选择指定乘车人")
        }
        if (stage == Stage.SEAT && pageState == OfficialPageState.ORDER_CONFIRM) {
            stage = Stage.ORDER
            TaskStore(this).updateStatus(TaskStatus.VALIDATING_ORDER, "车次已预订，正在核对订单确认页")
        }
        if (stage == Stage.PASSENGER && pageState == OfficialPageState.ORDER_CONFIRM) {
            stage = Stage.ORDER
            TaskStore(this).updateStatus(TaskStatus.VALIDATING_ORDER, "乘车人已确认，正在核对订单确认页")
        }

        val expectedPage = when (stage) {
            Stage.WAITING_PAGE, Stage.OPEN_SEARCH, Stage.DEPARTURE, Stage.ARRIVAL,
            Stage.DATE, Stage.SUBMIT_SEARCH, Stage.PREWARM, Stage.SALE_T0,
            Stage.COLD_START, Stage.OPENING_OFFICIAL_APP, Stage.OPENING_HOME,
            Stage.FILLING_SEARCH_FORM, Stage.WAITING_SEARCH_RESULT,
            Stage.WAITING_RESULT, Stage.DONE -> null
            Stage.VALIDATING_SEARCH, Stage.TRAIN -> OfficialPageState.SEARCH_RESULT
            Stage.SEAT -> OfficialPageState.SEAT_SELECTION
            Stage.PASSENGER -> OfficialPageState.PASSENGER_SELECTION
            Stage.ORDER -> OfficialPageState.ORDER_CONFIRM
        }
        if (stage != Stage.DONE && System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) return
        if (expectedPage != null && pageState != expectedPage) {
            if (pageState == OfficialPageState.SEARCH_RESULT_LOADING || pageState == OfficialPageState.SEARCH_RESULT_PARTIAL ||
                (stage == Stage.SEAT && pageState == OfficialPageState.SEARCH_RESULT) ||
                (stage == Stage.PASSENGER && pageState == OfficialPageState.SEAT_SELECTION) ||
                (stage == Stage.ORDER && pageState == OfficialPageState.PASSENGER_SELECTION)) {
                expectedPageWaitEvents++
                recordDiagnostic(pageState, "等待页面刷新（当前 ${pageState.name}，预期 ${expectedPage.name}，第 ${expectedPageWaitEvents} 次）", evidenceSource = "PAGE_REFRESH_WAIT")
                if (expectedPageWaitEvents < MAX_PAGE_REFRESH_WAIT_EVENTS) return
            }
            takeover("官方 12306 页面与预期不一致（当前 ${pageState.name}，预期 ${expectedPage.name}），已停止自动操作")
            return
        }
        expectedPageWaitEvents = 0

        when (stage) {
            Stage.WAITING_PAGE -> handleWaitingPage(root, text, task)
            Stage.PREWARM -> handlePrewarm(text, task)
            Stage.SALE_T0 -> Unit
            Stage.COLD_START, Stage.OPENING_OFFICIAL_APP, Stage.OPENING_HOME -> Unit
            Stage.FILLING_SEARCH_FORM -> handleFillingSearchForm(root, text, task)
            Stage.OPEN_SEARCH -> handleOpeningSearch(root, text)
            Stage.DEPARTURE -> handleStationField(root, SearchField.DEPARTURE, task.from.name) {
                stage = Stage.ARRIVAL
                TaskStore(this).updateStatus(TaskStatus.FILLING_ARRIVAL, "出发站已确认，正在填写到达站")
            }
            Stage.ARRIVAL -> handleStationField(root, SearchField.ARRIVAL, task.to.name) {
                stage = Stage.DATE
                TaskStore(this).updateStatus(TaskStatus.FILLING_DATE, "到达站已确认，正在填写乘车日期")
            }
            Stage.DATE -> handleDateField(root, task)
            Stage.SUBMIT_SEARCH -> handleSearchSubmission(root, text, task)
            Stage.WAITING_SEARCH_RESULT -> handleSearchSubmission(root, text, task)
            Stage.VALIDATING_SEARCH -> validateAndSelectTrain(root, text, task)
            Stage.TRAIN -> {
                val check = classifySearchContext(text, task)
                when (check.status) {
                    SearchContextStatus.MATCH -> {
                        if (clickTrain(root, task)) {
                            stage = Stage.SEAT
                            TaskStore(this).updateStatus(TaskStatus.SELECTING_TRAIN_SEAT, "已定位目标车次，等待席别页面")
                        } else {
                            takeover("已核对查询结果，但未找到目标车次对应的可点击预订控件")
                        }
                    }
                    SearchContextStatus.MISSING -> waitForSearchContext(check.missing)
                    SearchContextStatus.CONFLICT -> takeover("查询结果页字段与任务冲突，未点击车次")
                    SearchContextStatus.WRONG_PAGE -> takeover("当前不是查询结果页，未点击车次")
                }
            }
            Stage.SEAT -> {
                when (selectSeatStep(root, task.seat)) {
                    InteractionResult.DONE -> {
                        actionWaitEvents = 0
                        stage = Stage.PASSENGER
                        TaskStore(this).updateStatus(TaskStatus.SELECTING_PASSENGER, "已刷新页面并进入乘车人选择")
                    }
                    InteractionResult.WAITING -> {
                        actionWaitEvents++
                        recordDiagnostic(pageState, "等待席别页面刷新或继续控件（第 ${actionWaitEvents} 次）", evidenceSource = "SEAT_PAGE_REFRESH")
                        if (actionWaitEvents >= MAX_ACTION_WAIT_EVENTS) takeover("未找到目标席别或席别页面继续控件")
                    }
                    InteractionResult.FAILED -> takeover("目标席别控件动作未派发，未继续操作")
                }
            }
            Stage.PASSENGER -> when (selectPassengerAndContinue(root, task.passengerName)) {
                PassengerResult.SELECTED -> {
                    actionWaitEvents = 0
                    TaskStore(this).recordEvent("已选择唯一同名乘车人，等待刷新后点击继续")
                }
                PassengerResult.CONTINUED -> {
                    actionWaitEvents = 0
                    stage = Stage.ORDER
                    TaskStore(this).updateStatus(TaskStatus.VALIDATING_ORDER, "已刷新页面并进入订单确认页")
                }
                PassengerResult.WAITING -> {
                    actionWaitEvents++
                    recordDiagnostic(pageState, "等待乘车人列表或继续控件（第 ${actionWaitEvents} 次）", evidenceSource = "PASSENGER_PAGE_REFRESH")
                    if (actionWaitEvents >= MAX_ACTION_WAIT_EVENTS) takeover("未找到唯一乘车人或乘车人页面继续控件")
                }
                PassengerResult.AMBIGUOUS -> takeover("官方 12306 中存在多个同名乘车人，请手动选择")
                PassengerResult.NOT_FOUND -> takeover("官方 12306 中找不到指定乘车人，请手动确认")
            }
            Stage.ORDER -> {
                val orderText = root.textContent()
                val orderMatchesTask = matchesToken(orderText, task.train.trainNo) &&
                    normalizeText(orderText).contains(normalizeText(task.seat)) &&
                    normalizeText(orderText).contains(normalizeText(task.passengerName))
                when {
                    !orderMatchesTask -> takeover("订单确认页字段与任务不一致，未提交订单")
                    !hasAdultTicketSelection(orderText) -> takeover("订单确认页未确认成人票，未提交订单")
                    else -> when (submitOrder(root, task)) {
                        SubmitResult.CLICKED -> {
                            stage = Stage.WAITING_RESULT
                            resultEvents = 0
                        }
                        SubmitResult.NO_BUTTON -> takeover("订单确认页未找到唯一可点击的“提交订单”控件")
                        SubmitResult.ALREADY_LOCKED -> resultUnknown("本任务已尝试提交，请到官方 12306 订单页核对；不会自动重试")
                        SubmitResult.CLICK_REJECTED -> takeover("系统未派发“提交订单”点击，未确认下单；请手动核对")
                    }
                }
            }
            Stage.WAITING_RESULT, Stage.DONE -> Unit
        }
    }

    override fun onInterrupt() {
        if (stage == Stage.WAITING_RESULT) {
            resultUnknown("无障碍服务在提交后中断，请到官方 12306 订单页核对")
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(staticRefresh)
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun handleWaitingPage(root: AccessibilityNodeInfo, text: String, task: TicketTask) {
        when (pageState) {
            OfficialPageState.HOME_PAGE, OfficialPageState.SEARCH_FORM -> {
                stage = Stage.OPEN_SEARCH
                formStartedAt = System.currentTimeMillis()
                formWaitEvents = 0
                TaskStore(this).updateStatus(TaskStatus.OPENING_SEARCH, "已打开官方 12306，正在定位查询入口")
                handleOpeningSearch(root, text)
            }
            OfficialPageState.SEARCH_RESULT -> {
                stage = Stage.VALIDATING_SEARCH
                TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "正在核对官方查询结果并定位目标车次")
                validateAndSelectTrain(root, text, task)
            }
            OfficialPageState.SEARCH_RESULT_LOADING, OfficialPageState.SEARCH_RESULT_PARTIAL ->
                waitForFormProgress("等待官方查询结果页加载")
            else -> waitForFormProgress("等待官方 12306 查询页面")
        }
    }

    private fun handleColdStartPage(root: AccessibilityNodeInfo, text: String, task: TicketTask) {
        when (pageState) {
            OfficialPageState.HOME_PAGE -> {
                stage = Stage.OPENING_HOME
                TaskStore(this).recordColdStartState(TaskStatus.OPENING_HOME, "已检测到官方 12306 首页，正在定位车票入口")
                handleOpeningSearch(root, text)
            }
            OfficialPageState.SEARCH_FORM -> {
                stage = Stage.FILLING_SEARCH_FORM
                TaskStore(this).recordColdStartState(TaskStatus.FILLING_SEARCH_FORM, "已检测到车票查询表单，开始填写任务信息")
                handleFillingSearchForm(root, text, task)
            }
            else -> waitForColdStart("等待官方首页或车票查询表单")
        }
    }

    private fun handleFillingSearchForm(root: AccessibilityNodeInfo, text: String, task: TicketTask) {
        if (pageState != OfficialPageState.HOME_PAGE && pageState != OfficialPageState.SEARCH_FORM) {
            waitForColdStart("等待官方车票查询表单")
            return
        }
        stage = Stage.DEPARTURE
        TaskStore(this).updateStatus(TaskStatus.FILLING_DEPARTURE, "正在自动填写出发站")
        handleStationField(root, SearchField.DEPARTURE, task.from.name) {
            stage = Stage.ARRIVAL
            TaskStore(this).updateStatus(TaskStatus.FILLING_ARRIVAL, "出发站已确认，正在填写到达站")
        }
    }

    private fun handlePrewarm(text: String, task: TicketTask) {
        when (pageState) {
            OfficialPageState.SEARCH_RESULT -> {
                val check = classifySearchContext(text, task)
                recordDiagnostic(
                    pageState,
                    contextStatus = check.status.name,
                    missingEvidence = check.missing.joinToString("、"),
                    snapshotFingerprint = searchSnapshotFingerprint(text),
                    evidenceSource = "PREWARM_CONTEXT"
                )
                if (check.status == SearchContextStatus.MATCH) {
                    recordDiagnostic(
                        pageState,
                        "任务一致的官方结果页已预热，等待开售",
                        evidenceSource = "PREWARM_READY"
                    )
                    TaskStore(this).recordEvent("官方结果页已预热：开售时将直接定位目标车次")
                } else if (check.status == SearchContextStatus.MISSING) {
                    recordDiagnostic(
                        pageState,
                        "官方结果页证据不完整，等待用户补齐：${check.missing.joinToString("、")}",
                        evidenceSource = "PREWARM_CONTEXT_MISMATCH"
                    )
                    TaskStore(this).recordEvent("官方结果页证据不完整，请在开售前保持日期、站点、车次和席别可见")
                } else {
                    TaskStore(this).recordEvent("预热页面与任务冲突或不是结果页，开售时将要求人工接管")
                }
            }
            OfficialPageState.HOME_PAGE, OfficialPageState.SEARCH_FORM -> {
                recordDiagnostic(
                    pageState,
                    "官方首页已打开，请在开售前准备任务一致的结果页",
                    evidenceSource = "PREWARM_HOME"
                )
                TaskStore(this).recordEvent("官方首页已打开；为降低开售延迟，请提前查询任务一致的结果页")
            }
            else -> {
                recordDiagnostic(pageState, "等待官方结果页预热", evidenceSource = "PREWARM_WAITING")
            }
        }
    }

    private fun handleOpeningSearch(root: AccessibilityNodeInfo, text: String) {
        when (pageState) {
            OfficialPageState.SEARCH_RESULT -> {
                val task = TaskStore(this).load() ?: return
                stage = Stage.VALIDATING_SEARCH
                TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "正在核对官方查询结果并定位目标车次")
                validateAndSelectTrain(root, text, task)
            }
            OfficialPageState.HOME_PAGE, OfficialPageState.SEARCH_FORM -> {
                if (hasSearchForm(text)) {
                    stage = Stage.FILLING_SEARCH_FORM
                    TaskStore(this).recordColdStartState(TaskStatus.FILLING_SEARCH_FORM, "已定位官方车票查询表单，开始填写任务信息")
                    stage = Stage.DEPARTURE
                    TaskStore(this).updateStatus(TaskStatus.FILLING_DEPARTURE, "正在自动填写出发站")
                    handleStationField(root, SearchField.DEPARTURE, TaskStore(this).load()?.from?.name.orEmpty()) {
                        stage = Stage.ARRIVAL
                        TaskStore(this).updateStatus(TaskStatus.FILLING_ARRIVAL, "出发站已确认，正在填写到达站")
                    }
                    return
                }
                when (searchInteractor.openTickets(root)) {
                    InteractionResult.DONE -> {
                        lastActionAt = System.currentTimeMillis()
                        stage = Stage.FILLING_SEARCH_FORM
                        TaskStore(this).recordColdStartState(TaskStatus.FILLING_SEARCH_FORM, "已打开官方车票查询，开始填写任务信息")
                        stage = Stage.DEPARTURE
                        TaskStore(this).updateStatus(TaskStatus.FILLING_DEPARTURE, "已打开车票查询，正在填写出发站")
                    }
                    InteractionResult.WAITING -> waitForFormProgress("正在定位官方车票查询入口")
                    InteractionResult.FAILED -> takeover("官方 12306 车票查询入口动作未派发")
                }
            }
            else -> waitForFormProgress("等待官方 12306 查询表单")
        }
    }

    private fun handleStationField(
        root: AccessibilityNodeInfo,
        field: SearchField,
        stationName: String,
        onDone: () -> Unit
    ) {
        if (stationName.isBlank()) {
            takeover("任务缺少${if (field == SearchField.DEPARTURE) "出发站" else "到达站"}，无法自动查询")
            return
        }
        when (searchInteractor.fillStation(root, field, stationName)) {
            InteractionResult.DONE -> {
                formEvidence += field
                formWaitEvents = 0
                lastActionAt = System.currentTimeMillis()
                onDone()
            }
            InteractionResult.WAITING -> {
                waitForFormProgress("等待${if (field == SearchField.DEPARTURE) "出发站" else "到达站"}候选项")
            }
            InteractionResult.FAILED -> takeover("官方 12306 ${if (field == SearchField.DEPARTURE) "出发站" else "到达站"}控件无法操作")
        }
    }

    private fun handleDateField(root: AccessibilityNodeInfo, task: TicketTask) {
        when (searchInteractor.fillDate(root, task.date)) {
            InteractionResult.DONE -> {
                formEvidence += SearchField.DATE
                formWaitEvents = 0
                lastActionAt = System.currentTimeMillis()
                stage = Stage.SUBMIT_SEARCH
                TaskStore(this).updateStatus(TaskStatus.SUBMITTING_SEARCH, "日期已确认，正在查询目标车次")
            }
            InteractionResult.WAITING -> {
                waitForFormProgress("等待乘车日期控件更新")
            }
            InteractionResult.FAILED -> takeover("官方 12306 乘车日期控件无法操作")
        }
    }

    private fun handleSearchSubmission(root: AccessibilityNodeInfo, text: String, task: TicketTask) {
        if (pageState == OfficialPageState.SEARCH_RESULT) {
            stage = Stage.VALIDATING_SEARCH
            TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "查询完成，正在核对目标车次")
            validateAndSelectTrain(root, text, task)
            return
        }
        if (pageState != OfficialPageState.HOME_PAGE && pageState != OfficialPageState.SEARCH_FORM) {
            waitForFormProgress("查询已发送，等待官方结果页")
            return
        }
        if (searchActionSent) {
            waitForFormProgress("查询已发送，等待官方结果页")
            return
        }
        when (searchInteractor.submitSearch(root)) {
            InteractionResult.DONE -> {
                searchActionSent = true
                formWaitEvents = 0
                lastActionAt = System.currentTimeMillis()
                stage = Stage.WAITING_SEARCH_RESULT
                TaskStore(this).recordColdStartState(TaskStatus.WAITING_SEARCH_RESULT, "已发送查询动作，等待官方结果页")
            }
            InteractionResult.WAITING -> waitForFormProgress("正在定位查询按钮")
            InteractionResult.FAILED -> takeover("官方 12306 查询按钮动作未派发")
        }
    }

    private fun hasSearchForm(text: String): Boolean {
        val normalized = normalizeText(text)
        val fieldCount = listOf("出发地", "出发站", "到达地", "到达站", "乘车日期", "出发日期")
            .count(normalized::contains)
        return fieldCount >= 2 && listOf("查询", "搜索车票", "查询车票").any(normalized::contains)
    }

    private fun waitForFormProgress(reason: String) {
        formWaitEvents++
        val elapsed = if (formStartedAt == 0L) 0L else System.currentTimeMillis() - formStartedAt
        val now = System.currentTimeMillis()
        val shouldPersist = formWaitEvents == 1 || now - lastProgressRecordAt >= PROGRESS_RECORD_INTERVAL_MS
        if (shouldPersist) {
            lastProgressRecordAt = now
            recordDiagnostic(pageState, "$reason（第 ${formWaitEvents} 次等待）")
        }
        if (formWaitEvents >= MAX_FORM_WAIT_EVENTS || elapsed >= FORM_TIMEOUT_MS) {
            takeover(reason + "超时，无法确认官方控件")
        } else if (shouldPersist) {
            TaskStore(this).recordEvent(reason)
        }
    }

    private fun waitForColdStart(reason: String) {
        coldStartWaitEvents++
        recordDiagnostic(pageState, "$reason（第 ${coldStartWaitEvents} 次等待）", evidenceSource = "COLD_START_WAIT")
        val startedAt = TaskStore(this).load()?.coldStartAt ?: 0L
        val elapsed = if (startedAt > 0L) System.currentTimeMillis() - startedAt else 0L
        if (elapsed >= COLD_START_TIMEOUT_MS || (startedAt == 0L && coldStartWaitEvents >= MAX_COLD_START_WAIT_EVENTS)) {
            takeover("官方 App 冷启动或首页导航超时：$reason")
        } else {
            val status = when (stage) {
                Stage.COLD_START, Stage.SALE_T0 -> TaskStatus.COLD_START
                Stage.OPENING_OFFICIAL_APP -> TaskStatus.OPENING_OFFICIAL_APP
                Stage.OPENING_HOME -> TaskStatus.OPENING_HOME
                Stage.FILLING_SEARCH_FORM, Stage.OPEN_SEARCH, Stage.DEPARTURE, Stage.ARRIVAL, Stage.DATE -> TaskStatus.FILLING_SEARCH_FORM
                else -> TaskStatus.WAITING_SEARCH_RESULT
            }
            TaskStore(this).recordColdStartState(status, reason)
        }
    }

    private fun isColdStartStage(value: Stage): Boolean = value in setOf(
        Stage.COLD_START,
        Stage.OPENING_OFFICIAL_APP,
        Stage.OPENING_HOME,
        Stage.FILLING_SEARCH_FORM
    )

    private fun waitForSearchContext(missing: List<String>) {
        fastPathWaitEvents++
        val detail = missing.ifEmpty { listOf("结果页字段") }.joinToString("、")
        recordDiagnostic(
            pageState,
            "等待结果页证据补齐：$detail（第 ${fastPathWaitEvents} 次）",
            evidenceSource = "SEARCH_CONTEXT_MISSING",
            missingEvidence = detail
        )
        if (fastPathWaitEvents >= MAX_RESULT_CONTEXT_WAIT_EVENTS) {
            takeover("官方结果页证据持续缺失：$detail")
        } else {
            TaskStore(this).recordEvent("等待官方结果页证据补齐：$detail")
        }
    }

    private fun validateAndSelectTrain(root: AccessibilityNodeInfo, text: String, task: TicketTask) {
        TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "正在核对官方查询结果并定位目标车次")
        val check = classifySearchContext(text, task)
        recordDiagnostic(
            pageState,
            contextStatus = check.status.name,
            missingEvidence = check.missing.joinToString("、"),
            evidenceSource = "SEARCH_CONTEXT_CHECK"
        )
        when (check.status) {
            SearchContextStatus.MISSING -> {
                waitForSearchContext(check.missing)
                return
            }
            SearchContextStatus.CONFLICT -> {
                TaskStore(this).clearSearchSnapshot()
                takeover("官方查询结果与任务冲突（${check.conflicts.joinToString("、")})，未点击车次")
                return
            }
            SearchContextStatus.WRONG_PAGE -> {
                TaskStore(this).clearSearchSnapshot()
                takeover("当前不是官方查询结果页，未点击首页入口或返回按钮")
                return
            }
            SearchContextStatus.MATCH -> Unit
        }
        stage = Stage.TRAIN
        TaskStore(this).updateStatus(TaskStatus.SELECTING_TRAIN_SEAT, "已核对查询结果页，正在定位目标车次")
        if (clickTrain(root, task)) {
            stage = Stage.SEAT
            TaskStore(this).updateStatus(TaskStatus.SELECTING_TRAIN_SEAT, "已定位目标车次，等待席别页面")
        } else {
            takeover("已核对查询结果，但未找到目标车次对应的可点击预订控件")
        }
    }

    private fun matchesExecutionSearchContext(text: String, task: TicketTask): Boolean {
        return classifySearchContext(text, task).status == SearchContextStatus.MATCH
    }

    private fun handleSubmitResult(text: String, task: TicketTask, rootPackage: String?) {
        resultEvents++
        val now = System.currentTimeMillis()
        val evidenceWindowOpen = pendingEvidenceStartedAt > 0L &&
            now - pendingEvidenceStartedAt <= PENDING_EVIDENCE_WINDOW_MS &&
            pendingEvidenceEvents < MAX_PENDING_EVIDENCE_EVENTS
        if (pendingEvidenceStartedAt == 0L || evidenceWindowOpen) {
            if (!evidenceWindowOpen) {
                pendingEvidence = ""
                pendingEvidenceStartedAt = now
                pendingEvidenceEvents = 0
            }
            pendingEvidence = (pendingEvidence + " " + text).takeLast(MAX_EVIDENCE_LENGTH)
            pendingEvidenceEvents++
        }
        if (isPendingPaymentConfirmationAllowed(stage.name, rootPackage, pendingEvidence, task)) {
            pageState = OfficialPageState.PENDING_PAYMENT
            recordDiagnostic(
                OfficialPageState.PENDING_PAYMENT,
                "已核对官方待支付订单字段",
                rootPackage = rootPackage,
                evidenceSource = "VERIFIED_ORDER_FIELDS"
            )
            TaskStore(this).updateStatus(TaskStatus.PENDING_PAYMENT, "已核对官方待支付订单字段")
            notify("已确认官方待支付订单，请在 12306 手动付款")
            stopService(android.content.Intent(this, TicketAutomationService::class.java))
            stage = Stage.DONE
            return
        }
        when (pageState) {
            OfficialPageState.PROCESSING, OfficialPageState.PENDING_PAYMENT ->
                TaskStore(this).updateStatus(TaskStatus.WAITING_SERVER_RESULT, "已发送提交点击，官方正在处理；正在等待订单结果")
            OfficialPageState.ORDER_CONFIRM ->
                TaskStore(this).updateStatus(TaskStatus.WAITING_SERVER_RESULT, "提交后仍停留在订单确认页，请等待官方结果")
            else -> if (resultEvents >= MAX_UNKNOWN_RESULT_EVENTS) {
                resultUnknown("提交后连续无法确认官方结果（${pageState.name}），请到官方订单页核对")
            } else {
                TaskStore(this).updateStatus(TaskStatus.WAITING_SERVER_RESULT, "提交后页面暂未识别，继续等待官方结果")
            }
        }
    }

    private fun clickTrain(root: AccessibilityNodeInfo, task: TicketTask): Boolean {
        val target = findTrainAction(root, task) ?: return false
        val clicked = clickNodeOrParent(target)
        recordDiagnostic(pageState, if (clicked) "已点击目标车次对应预订" else "目标车次预订点击失败")
        if (clicked) lastActionAt = System.currentTimeMillis()
        return clicked
    }

    private fun selectSeatStep(root: AccessibilityNodeInfo, seat: String): InteractionResult {
        if (seatSelected) {
            val snapshotChanged = seatBeforeClickFingerprint?.let {
                it != accessibilitySnapshotFingerprint(root)
            } == true
            if (!snapshotChanged) {
                recordDiagnostic(pageState, "等待席别点击后的页面刷新（快照未变化）", evidenceSource = "SEAT_ACTION_WAIT")
                return InteractionResult.WAITING
            }
            val next = findActionNode(root, listOf("预订", "下一步", "确认")) ?: return InteractionResult.WAITING
            if (!clickNodeOrParent(next)) return InteractionResult.FAILED
            seatSelected = false
            seatBeforeClickFingerprint = null
            lastActionAt = System.currentTimeMillis()
            recordDiagnostic(pageState, "已刷新席别页面并点击继续")
            return InteractionResult.DONE
        }
        val pageText = root.textContent()
        val unavailable = seatUnavailableEvidence(pageText, seat)
        if (unavailable) {
            takeover("官方 12306 中所选席别当前无票，请手动选择")
            return InteractionResult.FAILED
        }
        val matches = findTextNodes(root) { value -> normalizeText(value) == normalizeText(seat) }
            .filter { it.childCount == 0 }
        if (matches.size != 1) return InteractionResult.WAITING
        val beforeClickFingerprint = accessibilitySnapshotFingerprint(root)
        if (!clickNodeOrParent(matches.first())) return InteractionResult.FAILED
        seatSelected = true
        seatBeforeClickFingerprint = beforeClickFingerprint
        lastActionAt = System.currentTimeMillis()
        recordDiagnostic(pageState, "已点击席别：$seat")
        return InteractionResult.WAITING
    }

    private fun selectPassengerAndContinue(root: AccessibilityNodeInfo, passenger: String): PassengerResult {
        if (passengerSelected) {
            val snapshotChanged = passengerBeforeClickFingerprint?.let {
                it != accessibilitySnapshotFingerprint(root)
            } == true
            if (!snapshotChanged) {
                recordDiagnostic(pageState, "等待乘车人点击后的页面刷新（快照未变化）", evidenceSource = "PASSENGER_ACTION_WAIT")
                return PassengerResult.WAITING
            }
            val next = findActionNode(root, listOf("确认", "下一步")) ?: return PassengerResult.WAITING
            if (!clickNodeOrParent(next)) return PassengerResult.NOT_FOUND
            passengerSelected = false
            passengerBeforeClickFingerprint = null
            lastActionAt = System.currentTimeMillis()
            recordDiagnostic(pageState, "已刷新乘车人页面并点击继续")
            return PassengerResult.CONTINUED
        }
        val matches = findTextNodes(root) { value -> normalizeText(value) == normalizeText(passenger) }
            .filter { it.childCount == 0 }
        if (matches.isEmpty()) return PassengerResult.WAITING
        if (matches.size > 1) return PassengerResult.AMBIGUOUS
        val beforeClickFingerprint = accessibilitySnapshotFingerprint(root)
        if (!clickNodeOrParent(matches.first())) return PassengerResult.NOT_FOUND
        passengerSelected = true
        passengerBeforeClickFingerprint = beforeClickFingerprint
        lastActionAt = System.currentTimeMillis()
        recordDiagnostic(pageState, "已选择目标乘车人（姓名已脱敏）")
        return PassengerResult.SELECTED
    }

    private fun submitOrder(root: AccessibilityNodeInfo, task: TicketTask): SubmitResult {
        val submit = findActionNode(root, listOf("提交订单")) ?: return SubmitResult.NO_BUTTON
        val gate = PersistentSubmitGate(getSharedPreferences("submit_gate", MODE_PRIVATE), submitGateKey(task))
        if (!gate.tryAcquire()) return SubmitResult.ALREADY_LOCKED
        val clicked = clickNodeOrParent(submit)
        if (!clicked) {
            gate.releaseAfterClickRejected()
            recordDiagnostic(OfficialPageState.ORDER_CONFIRM, "提交按钮点击未派发，提交锁已释放")
            return SubmitResult.CLICK_REJECTED
        }
        lastActionAt = System.currentTimeMillis()
        TaskStore(this).updateStatus(TaskStatus.SUBMIT_ACTION_SENT, "已发送“提交订单”点击，等待官方 12306 确认")
        recordDiagnostic(OfficialPageState.ORDER_CONFIRM, "提交订单点击已派发，等待服务器结果")
        notify("已发送提交订单点击，正在等待官方 12306 确认；不会自动重复提交")
        return SubmitResult.CLICKED
    }

    private fun findTrainAction(root: AccessibilityNodeInfo, task: TicketTask): AccessibilityNodeInfo? {
        val trainNodes = findTextNodes(root) { value -> matchesToken(value, task.train.trainNo) }
        val actions = trainNodes.mapNotNull { node ->
            var parent = node.parent
            repeat(8) {
                if (parent == null) return@repeat
                val containerText = parent.textContent()
                if (normalizeText(containerText).contains(normalizeText(task.from.name)) &&
                    normalizeText(containerText).contains(normalizeText(task.to.name)) &&
                    normalizeText(containerText).contains(normalizeText(task.seat))
                ) {
                    return@mapNotNull findActionNode(parent, listOf("预订"))
                }
                parent = parent.parent
            }
            null
        }.distinctBy { System.identityHashCode(it) }
        return actions.singleOrNull()
    }

    private fun findActionNode(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val targets = findTextNodes(root) { value ->
            labels.any { label -> normalizeText(value).contains(normalizeText(label)) }
        }.mapNotNull { node ->
            if (node.isClickable) node else clickableParent(node)
        }.distinctBy { System.identityHashCode(it) }
        return targets.singleOrNull()
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        repeat(5) {
            if (parent == null) return false
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        return false
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        repeat(5) {
            if (parent?.isClickable == true) return parent
            parent = parent?.parent
        }
        return null
    }

    private fun findTextNodes(root: AccessibilityNodeInfo, predicate: (String) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
            if (node == null || (!includeRoot && !node.isVisibleToUser)) return
            val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (values.any(predicate)) result += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root, includeRoot = true)
        return result
    }

    private fun AccessibilityNodeInfo.textContent(): String = buildString {
        fun walk(node: AccessibilityNodeInfo?, includeRoot: Boolean = false) {
            if (node == null || (!includeRoot && !node.isVisibleToUser)) return
            node.text?.let { append(' ').append(it) }
            node.contentDescription?.let { append(' ').append(it) }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(this@textContent, includeRoot = true)
    }

    /** Structural, short-lived fingerprint used to reject stale node actions. */
    private fun accessibilitySnapshotFingerprint(root: AccessibilityNodeInfo): String {
        return lightweightAccessibilityFingerprint(root)
    }

    private fun handleEmptyTree(reason: String) {
        emptyTreeEvents++
        recordDiagnostic(OfficialPageState.UNKNOWN, "$reason（连续 ${emptyTreeEvents} 次）")
        if (isColdStartStage(stage) || stage == Stage.SALE_T0) {
            waitForColdStart(reason)
        } else if (emptyTreeEvents >= MAX_EMPTY_TREE_EVENTS) {
            takeover("连续多次无法读取官方 12306 页面，已停止自动操作；请确认无障碍服务已启用")
        } else {
            TaskStore(this).updateStatus(TaskStatus.WAITING_OFFICIAL_PAGE, reason)
        }
    }

    private fun recordDiagnostic(
        page: OfficialPageState,
        action: String? = null,
        rootPackage: String? = null,
        evidenceSource: String? = null,
        contextStatus: String? = null,
        missingEvidence: String? = null,
        snapshotFingerprint: String? = null
    ) {
        val now = System.currentTimeMillis()
        val key = listOf(
            page.name,
            stage.name,
            action?.replace(Regex("（第 \\d+ 次等待）"), "（等待）"),
            rootPackage,
            evidenceSource,
            contextStatus,
            missingEvidence,
            snapshotFingerprint
        ).joinToString("|")
        if (key == lastDiagnosticKey && now - lastDiagnosticAt < DIAGNOSTIC_REPEAT_INTERVAL_MS) return
        lastDiagnosticKey = key
        lastDiagnosticAt = now
        val actionOutcome = action?.let {
            when {
                it.contains("失败") || it.contains("未派发") || it.contains("无法") -> "REJECTED"
                it.contains("等待") -> "WAITING"
                else -> "SENT_OR_OBSERVED"
            }
        }
        TaskStore(this).recordAccessibilityEvent(
            pageState = page.name,
            action = action,
            automationStage = stage.name,
            rootPackage = rootPackage,
            evidenceSource = evidenceSource,
            contextStatus = contextStatus,
            missingEvidence = missingEvidence,
            contextAt = if (contextStatus != null) System.currentTimeMillis() else null,
            snapshotFingerprint = snapshotFingerprint,
            actionAt = if (action != null) System.currentTimeMillis() else null,
            actionOutcome = actionOutcome
        )
    }

    private fun takeover(reason: String) {
        if (stage == Stage.DONE) return
        stage = Stage.DONE
        TaskStore(this).updateStatus(TaskStatus.TAKEOVER, "需要人工接管：$reason")
        stopService(android.content.Intent(this, TicketAutomationService::class.java))
        notify(reason)
    }

    private fun submitRejected(reason: String) {
        if (stage == Stage.DONE) return
        stage = Stage.DONE
        TaskStore(this).updateStatus(TaskStatus.SUBMIT_REJECTED, reason)
        stopService(android.content.Intent(this, TicketAutomationService::class.java))
        notify("官方 12306 未创建待支付订单：$reason")
    }

    private fun resultUnknown(reason: String) {
        if (stage == Stage.DONE) return
        stage = Stage.DONE
        TaskStore(this).updateStatus(TaskStatus.RESULT_UNKNOWN, reason)
        stopService(android.content.Intent(this, TicketAutomationService::class.java))
        notify("$reason；不会自动重试提交")
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "行程助手状态", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("行程助手")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build())
    }

    private fun failureCategory(text: String): String = when {
        normalizeText(text).contains("重复订单") || normalizeText(text).contains("已有未完成订单") -> "重复订单或已有未完成订单"
        normalizeText(text).contains("席位不足") || normalizeText(text).contains("余票不足") -> "席位不足"
        normalizeText(text).contains("网络") -> "网络异常"
        else -> "官方返回明确失败页面"
    }

    private fun stageFor(status: TaskStatus): Stage = when (status) {
        TaskStatus.PREPARING -> Stage.PREWARM
        TaskStatus.SALE_T0 -> Stage.SALE_T0
        TaskStatus.COLD_START -> Stage.COLD_START
        TaskStatus.OPENING_OFFICIAL_APP -> Stage.OPENING_OFFICIAL_APP
        TaskStatus.OPENING_HOME -> Stage.OPENING_HOME
        TaskStatus.FILLING_SEARCH_FORM -> Stage.FILLING_SEARCH_FORM
        TaskStatus.WAITING_SEARCH_RESULT -> Stage.WAITING_SEARCH_RESULT
        TaskStatus.WAITING_OFFICIAL_PAGE, TaskStatus.OBSERVING, TaskStatus.SEARCHING -> Stage.WAITING_PAGE
        TaskStatus.OPENING_SEARCH -> Stage.OPEN_SEARCH
        TaskStatus.FILLING_DEPARTURE -> Stage.DEPARTURE
        TaskStatus.FILLING_ARRIVAL -> Stage.ARRIVAL
        TaskStatus.FILLING_DATE -> Stage.DATE
        TaskStatus.SUBMITTING_SEARCH -> Stage.SUBMIT_SEARCH
        TaskStatus.VALIDATING_SEARCH_RESULT -> Stage.VALIDATING_SEARCH
        TaskStatus.SELECTING_TRAIN_SEAT -> Stage.TRAIN
        TaskStatus.SELECTING_PASSENGER -> Stage.PASSENGER
        TaskStatus.VALIDATING_ORDER -> Stage.ORDER
        TaskStatus.SUBMIT_ACTION_SENT, TaskStatus.WAITING_SERVER_RESULT -> Stage.WAITING_RESULT
        else -> Stage.WAITING_PAGE
    }

    companion object {
        const val OFFICIAL_PACKAGE = "com.MobileTicket"
        @Volatile private var activeInstance: TicketAccessibilityService? = null
        @Volatile private var lastOfficialRootAt: Long = 0L

        fun signalSaleT0(context: Context, taskId: String) {
            val service = activeInstance
            if (service == null) {
                // The accessibility service may connect only after the cold
                // start launches the official app. Keep the task recoverable
                // and let onServiceConnected/staticRefresh pick up SALE_T0.
                val task = TaskStore(context).load()
                if (task?.taskId == taskId && task.status in setOf(TaskStatus.SALE_T0, TaskStatus.COLD_START, TaskStatus.OPENING_OFFICIAL_APP)) {
                    TaskStore(context).recordColdStartState(TaskStatus.COLD_START, "等待无障碍服务连接后继续官方 App 冷启动")
                } else {
                    TaskStore(context).recordEvent("开售时无障碍服务实例暂不可用，等待服务连接")
                }
                return
            }
            service.mainHandler.post { service.activateSaleT0(taskId) }
        }

        fun hasRecentOfficialWindow(context: Context, maxAgeMs: Long = 30_000L): Boolean {
            val task = TaskStore(context).load() ?: return false
            val eventAt = task.lastAccessibilityEventAt ?: return false
            return activeInstance != null && task.lastRootPackage == OFFICIAL_PACKAGE &&
                task.lastPageState in setOf(
                    OfficialPageState.HOME_PAGE.name,
                    OfficialPageState.SEARCH_FORM.name,
                    OfficialPageState.SEARCH_RESULT.name,
                    OfficialPageState.SEARCH_RESULT_PARTIAL.name,
                    OfficialPageState.SEARCH_RESULT_LOADING.name,
                    OfficialPageState.LAUNCHING.name,
                    OfficialPageState.UNKNOWN.name,
                    OfficialPageState.POPUP.name,
                    OfficialPageState.STATION_PICKER.name,
                    OfficialPageState.DATE_PICKER.name
                ) && System.currentTimeMillis() - eventAt <= maxAgeMs &&
                System.currentTimeMillis() - lastOfficialRootAt <= maxAgeMs
        }

        private const val CHANNEL = "ticket_takeover"
        private const val NOTIFICATION_ID = 102
        private const val ACTION_COOLDOWN_MS = 180L
        private const val MAX_EMPTY_TREE_EVENTS = 3
        private const val MAX_FORM_WAIT_EVENTS = 120
        private const val FORM_TIMEOUT_MS = 60_000L
        private const val MAX_UNKNOWN_RESULT_EVENTS = 3
        private const val MAX_FAST_PATH_WAIT_EVENTS = 3
        private const val MAX_COLD_START_WAIT_EVENTS = 120
        private const val COLD_START_TIMEOUT_MS = 90_000L
        private const val MAX_RESULT_CONTEXT_WAIT_EVENTS = 12
        private const val MAX_PAGE_REFRESH_WAIT_EVENTS = 12
        private const val MAX_ACTION_WAIT_EVENTS = 12
        private const val STATIC_REFRESH_INTERVAL_MS = 300L
        private const val PROGRESS_RECORD_INTERVAL_MS = 750L
        private const val DIAGNOSTIC_REPEAT_INTERVAL_MS = 300L
        private val STATIC_REFRESH_STATUSES = setOf(
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
            TaskStatus.WAITING_SERVER_RESULT
        )
        private const val MAX_EVIDENCE_LENGTH = 8_000
        private const val MAX_PENDING_EVIDENCE_EVENTS = 5
        private const val PENDING_EVIDENCE_WINDOW_MS = 10_000L
        private val COLD_START_STATUSES = setOf(
            TaskStatus.COLD_START,
            TaskStatus.OPENING_OFFICIAL_APP,
            TaskStatus.OPENING_HOME,
            TaskStatus.FILLING_SEARCH_FORM,
            TaskStatus.WAITING_SEARCH_RESULT
        )
        private val ACTIVE_STATUSES = setOf(
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
    }

    private enum class Stage {
        WAITING_PAGE, PREWARM, SALE_T0, COLD_START, OPENING_OFFICIAL_APP, OPENING_HOME,
        FILLING_SEARCH_FORM, WAITING_SEARCH_RESULT, OPEN_SEARCH, DEPARTURE, ARRIVAL, DATE, SUBMIT_SEARCH,
        VALIDATING_SEARCH, TRAIN, SEAT, PASSENGER, ORDER, WAITING_RESULT, DONE
    }
    private enum class PassengerResult { SELECTED, CONTINUED, WAITING, AMBIGUOUS, NOT_FOUND }
    private enum class SubmitResult { CLICKED, NO_BUTTON, ALREADY_LOCKED, CLICK_REJECTED }
}

internal fun matchesToken(text: String, token: String): Boolean =
    Regex("(^|[^A-Za-z0-9])${Regex.escape(token)}([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)

internal fun isOfficialRootPackage(packageName: CharSequence?): Boolean =
    packageName?.toString() == TicketAccessibilityService.OFFICIAL_PACKAGE

internal fun isPendingPaymentConfirmationAllowed(
    stageName: String,
    rootPackage: String?,
    evidence: String,
    task: TicketTask
): Boolean = stageName == "WAITING_RESULT" &&
    rootPackage == TicketAccessibilityService.OFFICIAL_PACKAGE &&
    isVerifiedPendingPaymentPage(evidence, task)

internal fun normalizeText(text: String): String = text.replace(Regex("\\s+"), "").lowercase()

internal fun searchSnapshotFingerprint(text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normalizeText(text).toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

/** Strong page-context check; the keyword alone is only a diagnostic candidate. */
internal fun isPendingPaymentPage(text: String): Boolean {
    val normalized = normalizeText(text)
    val hasPaymentState = listOf("待支付", "待付款", "订单待支付", "未支付订单", "支付倒计时")
        .any(normalized::contains)
    val hasOrderContext = listOf("订单号", "订单编号", "剩余支付", "去支付", "取消订单", "订单详情")
        .any(normalized::contains) || (normalized.contains("订单") && normalized.contains("剩余"))
    return hasPaymentState && hasOrderContext
}

internal fun isVerifiedPendingPaymentPage(text: String, task: TicketTask): Boolean {
    val normalized = normalizeText(text)
    val dateParts = task.date.split('-')
    val hasTravelDate = dateParts.size == 3 && listOf(
        task.date,
        "${dateParts[0]}年${dateParts[1]}月${dateParts[2]}日",
        "${dateParts[0]}/${dateParts[1]}/${dateParts[2]}",
        "${dateParts[1]}月${dateParts[2]}日",
        "${dateParts[0]}年${dateParts[1].toIntOrNull() ?: dateParts[1]}月${dateParts[2].toIntOrNull() ?: dateParts[2]}日",
        "${dateParts[1].toIntOrNull() ?: dateParts[1]}月${dateParts[2].toIntOrNull() ?: dateParts[2]}日"
    ).any { normalized.contains(normalizeText(it)) }
    val hasOrderNumber = Regex("(?:订单号|订单编号)[:：#]?[A-Z0-9]{6,}", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
        Regex("订单[A-Z0-9]{8,}", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    val hasPrice = Regex("(?:¥|￥)\\s*\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?元").containsMatchIn(normalized)
    return isPendingPaymentPage(text) &&
        hasOrderNumber && hasTravelDate && matchesToken(text, task.train.trainNo) &&
        normalized.contains(normalizeText(task.seat)) && normalized.contains(normalizeText(task.passengerName)) && hasPrice
}

internal fun matchesSearchContext(text: String, task: TicketTask): Boolean {
    if (detectOfficialPageState(text) != OfficialPageState.SEARCH_RESULT) return false
    val normalized = normalizeText(text)
    val dateParts = task.date.split('-')
    val dateMatches = if (dateParts.size == 3) listOf(
        task.date,
        "${dateParts[0]}年${dateParts[1]}月${dateParts[2]}日",
        "${dateParts[0]}/${dateParts[1]}/${dateParts[2]}",
        "${dateParts[1]}月${dateParts[2]}日",
        "${dateParts[0]}年${dateParts[1].toIntOrNull() ?: dateParts[1]}月${dateParts[2].toIntOrNull() ?: dateParts[2]}日",
        "${dateParts[1].toIntOrNull() ?: dateParts[1]}月${dateParts[2].toIntOrNull() ?: dateParts[2]}日"
    ) else emptyList()
    return matchesToken(text, task.train.trainNo) &&
        normalized.contains(normalizeText(task.from.name)) &&
        normalized.contains(normalizeText(task.to.name)) &&
        normalized.contains(normalizeText(task.seat)) &&
        dateMatches.any { normalized.contains(normalizeText(it)) }
}

/** 普通菜单、订单说明和历史文案不能触发接管，必须是明确的安全验证/登录动作。 */
internal fun takeoverReason(text: String): String? {
    val normalized = normalizeText(text)
    return when {
        listOf("验证码", "滑块", "图形验证", "安全验证").any(normalized::contains) ->
            "官方 12306 要求验证码，请手动完成"
        listOf("请先登录", "登录后继续", "账号登录", "登录/注册", "会话已失效").any(normalized::contains) ->
            "官方 12306 需要登录，请手动完成"
        listOf(
            "请完成身份核验", "需要身份核验", "身份核验未完成", "身份核验失败",
            "请进行实名认证", "需要实名认证", "实名认证未完成", "实名认证失败"
        ).any(normalized::contains) ->
            "官方 12306 需要身份核验，请手动完成"
        listOf("风控验证", "访问过于频繁", "请求过于频繁", "操作频繁").any(normalized::contains) ->
            "官方 12306 触发了风控或限流，请手动处理"
        else -> null
    }
}
