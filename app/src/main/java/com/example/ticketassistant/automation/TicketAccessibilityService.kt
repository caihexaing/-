package com.example.ticketassistant.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TicketTask

/**
 * 仅辅助官方 12306 的公开页面操作。验证码、登录、身份核验、风控和未知页面永远交给用户。
 * 页面入口和每个动作都必须先被诊断记录；无法确认时停止，而不是静默等待或盲点。
 */
class TicketAccessibilityService : AccessibilityService() {
    private var lastActionAt = 0L
    private var activeTaskKey: String? = null
    private var stage = Stage.WAITING_PAGE
    private var pageState = OfficialPageState.LAUNCHING
    private var emptyTreeEvents = 0
    private var resultEvents = 0
    private var pendingEvidence = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != OFFICIAL_PACKAGE) return
        val task = TaskStore(this).load() ?: return
        if (!task.enabled || task.status !in ACTIVE_STATUSES) return

        val key = submitGateKey(task)
        if (activeTaskKey != key) {
            activeTaskKey = key
            stage = stageFor(task.status)
            pageState = OfficialPageState.LAUNCHING
            lastActionAt = 0L
            emptyTreeEvents = 0
            resultEvents = 0
            pendingEvidence = ""
        }

        val gate = PersistentSubmitGate(getSharedPreferences("submit_gate", MODE_PRIVATE), key)
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
        val text = root.textContent()
        if (text.isBlank()) {
            handleEmptyTree("官方 12306 页面节点为空")
            return
        }
        emptyTreeEvents = 0

        takeoverReason(text)?.let { reason ->
            recordDiagnostic(OfficialPageState.UNKNOWN, reason)
            takeover(reason)
            return
        }

        pageState = detectOfficialPageState(text)
        recordDiagnostic(pageState)
        if (pageState == OfficialPageState.POPUP) {
            takeover("官方 12306 出现公告或活动弹窗，请手动关闭后继续")
            return
        }
        if (pageState == OfficialPageState.SUBMIT_REJECTED) {
            if (stage == Stage.WAITING_RESULT) {
                submitRejected("官方 12306 未创建订单：${failureCategory(text)}")
            } else {
                takeover("官方 12306 页面显示错误或不可继续，尚未提交订单；请手动核对")
            }
            return
        }

        if (stage == Stage.WAITING_RESULT) {
            handleSubmitResult(text, task)
            return
        }

        if (pageState == OfficialPageState.PENDING_PAYMENT) {
            takeover("检测到待支付相关页面，但本任务尚未确认提交结果，请手动核对官方订单")
            return
        }
        if (pageState == OfficialPageState.HOME_PAGE) {
            takeover("官方 12306 当前在首页；请手动查询完全一致的日期、路线、车次和席别，并停留在查询结果页")
            return
        }
        if (pageState == OfficialPageState.PROCESSING) {
            takeover("官方 12306 正在处理，但任务尚未发送提交点击，请手动确认页面")
            return
        }

        val expectedPage = when (stage) {
            Stage.WAITING_PAGE, Stage.WAITING_RESULT, Stage.DONE -> null
            Stage.VALIDATING_SEARCH, Stage.TRAIN -> OfficialPageState.SEARCH_RESULT
            Stage.SEAT -> OfficialPageState.SEAT_SELECTION
            Stage.PASSENGER -> OfficialPageState.PASSENGER_SELECTION
            Stage.ORDER -> OfficialPageState.ORDER_CONFIRM
        }
        if (stage != Stage.DONE && System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) return
        if (expectedPage != null && pageState != expectedPage) {
            takeover("官方 12306 页面与预期不一致（当前 ${pageState.name}，预期 ${expectedPage.name}），已停止自动操作")
            return
        }

        when (stage) {
            Stage.WAITING_PAGE -> validateAndSelectTrain(root, text, task)
            Stage.VALIDATING_SEARCH -> validateAndSelectTrain(root, text, task)
            Stage.TRAIN -> {
                if (!matchesSearchContext(text, task)) {
                    takeover("查询结果页字段与任务不一致，未点击车次")
                } else if (clickTrain(root, task)) {
                    stage = Stage.SEAT
                    TaskStore(this).updateStatus(TaskStatus.SELECTING_TRAIN_SEAT, "已定位目标车次，等待席别页面")
                } else {
                    takeover("已核对查询结果，但未找到目标车次对应的可点击预订控件")
                }
            }
            Stage.SEAT -> {
                if (clickSeatAndContinue(root, task.seat)) {
                    stage = Stage.PASSENGER
                    TaskStore(this).updateStatus(TaskStatus.SELECTING_PASSENGER, "已选择目标席别，等待乘车人页面")
                } else {
                    takeover("未找到目标席别对应的可点击控件，未继续操作")
                }
            }
            Stage.PASSENGER -> when (selectPassengerAndContinue(root, task.passengerName)) {
                PassengerResult.SELECTED -> {
                    stage = Stage.ORDER
                    TaskStore(this).updateStatus(TaskStatus.VALIDATING_ORDER, "已选择唯一同名乘车人，等待订单确认页")
                }
                PassengerResult.AMBIGUOUS -> takeover("官方 12306 中存在多个同名乘车人，请手动选择")
                PassengerResult.NOT_FOUND -> takeover("官方 12306 中找不到指定乘车人，请手动确认")
            }
            Stage.ORDER -> {
                val orderText = root.textContent()
                if (!matchesToken(orderText, task.train.trainNo) ||
                    !normalizeText(orderText).contains(normalizeText(task.seat)) ||
                    !normalizeText(orderText).contains(normalizeText(task.passengerName))
                ) {
                    takeover("订单确认页字段与任务不一致，未提交订单")
                } else {
                    when (submitOrder(root, task)) {
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

    private fun validateAndSelectTrain(root: AccessibilityNodeInfo, text: String, task: TicketTask) {
        TaskStore(this).updateStatus(TaskStatus.VALIDATING_SEARCH_RESULT, "正在核对官方查询结果页字段")
        if (!matchesSearchContext(text, task)) {
            takeover("请在官方 12306 查询并停留在与任务完全一致的结果页（日期、站点、车次和席别均需匹配）")
            return
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

    private fun handleSubmitResult(text: String, task: TicketTask) {
        resultEvents++
        if (isPendingPaymentPage(text) || pendingEvidence.isNotBlank()) {
            pendingEvidence = (pendingEvidence + " " + text).takeLast(MAX_EVIDENCE_LENGTH)
        }
        if (pendingEvidence.isNotBlank() && isVerifiedPendingPaymentPage(pendingEvidence, task)) {
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

    private fun clickSeatAndContinue(root: AccessibilityNodeInfo, seat: String): Boolean {
        val pageText = root.textContent()
        val unavailable = Regex("${Regex.escape(seat)}.{0,12}(?:无票|无|--|\\*)").containsMatchIn(pageText)
        if (unavailable) {
            takeover("官方 12306 中所选席别当前无票，请手动选择")
            return false
        }
        val matches = findTextNodes(root) { value -> normalizeText(value) == normalizeText(seat) }
            .filter { it.childCount == 0 }
        if (matches.size != 1) return false
        if (!clickNodeOrParent(matches.first())) return false
        lastActionAt = System.currentTimeMillis()
        recordDiagnostic(pageState, "已点击席别：$seat")
        findActionNode(root, listOf("预订", "下一步", "确认"))?.let {
            if (clickNodeOrParent(it)) recordDiagnostic(pageState, "已点击席别页面继续")
        }
        return true
    }

    private fun selectPassengerAndContinue(root: AccessibilityNodeInfo, passenger: String): PassengerResult {
        val matches = findTextNodes(root) { value -> normalizeText(value) == normalizeText(passenger) }
            .filter { it.childCount == 0 }
        if (matches.isEmpty()) return PassengerResult.NOT_FOUND
        if (matches.size > 1) return PassengerResult.AMBIGUOUS
        if (!clickNodeOrParent(matches.first())) return PassengerResult.NOT_FOUND
        lastActionAt = System.currentTimeMillis()
        recordDiagnostic(pageState, "已选择目标乘车人（姓名已脱敏）")
        findActionNode(root, listOf("确认", "下一步"))?.let {
            if (clickNodeOrParent(it)) recordDiagnostic(pageState, "已点击乘车人页面继续")
        }
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
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (values.any(predicate)) result += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return result
    }

    private fun AccessibilityNodeInfo.textContent(): String = buildString {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { append(' ').append(it) }
            node.contentDescription?.let { append(' ').append(it) }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(this@textContent)
    }

    private fun handleEmptyTree(reason: String) {
        emptyTreeEvents++
        recordDiagnostic(OfficialPageState.UNKNOWN, "$reason（连续 ${emptyTreeEvents} 次）")
        if (emptyTreeEvents >= MAX_EMPTY_TREE_EVENTS) {
            takeover("连续多次无法读取官方 12306 页面，已停止自动操作；请确认无障碍服务已启用")
        } else {
            TaskStore(this).updateStatus(TaskStatus.WAITING_OFFICIAL_PAGE, reason)
        }
    }

    private fun recordDiagnostic(page: OfficialPageState, action: String? = null) {
        TaskStore(this).recordAccessibilityEvent(page.name, action)
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
        TaskStatus.WAITING_OFFICIAL_PAGE, TaskStatus.OBSERVING, TaskStatus.SEARCHING -> Stage.WAITING_PAGE
        TaskStatus.VALIDATING_SEARCH_RESULT -> Stage.VALIDATING_SEARCH
        TaskStatus.SELECTING_TRAIN_SEAT -> Stage.TRAIN
        TaskStatus.SELECTING_PASSENGER -> Stage.PASSENGER
        TaskStatus.VALIDATING_ORDER -> Stage.ORDER
        TaskStatus.SUBMIT_ACTION_SENT, TaskStatus.WAITING_SERVER_RESULT -> Stage.WAITING_RESULT
        else -> Stage.WAITING_PAGE
    }

    companion object {
        const val OFFICIAL_PACKAGE = "com.MobileTicket"
        private const val CHANNEL = "ticket_takeover"
        private const val NOTIFICATION_ID = 102
        private const val ACTION_COOLDOWN_MS = 900L
        private const val MAX_EMPTY_TREE_EVENTS = 3
        private const val MAX_UNKNOWN_RESULT_EVENTS = 3
        private const val MAX_EVIDENCE_LENGTH = 8_000
        private val ACTIVE_STATUSES = setOf(
            TaskStatus.WAITING_OFFICIAL_PAGE,
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

    private enum class Stage { WAITING_PAGE, VALIDATING_SEARCH, TRAIN, SEAT, PASSENGER, ORDER, WAITING_RESULT, DONE }
    private enum class PassengerResult { SELECTED, AMBIGUOUS, NOT_FOUND }
    private enum class SubmitResult { CLICKED, NO_BUTTON, ALREADY_LOCKED, CLICK_REJECTED }
}

internal fun matchesToken(text: String, token: String): Boolean =
    Regex("(^|[^A-Za-z0-9])${Regex.escape(token)}([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)

internal fun normalizeText(text: String): String = text.replace(Regex("\\s+"), "").lowercase()

internal fun isPendingPaymentPage(text: String): Boolean {
    val normalized = normalizeText(text)
    return listOf("待支付", "待付款", "订单待支付", "未支付订单", "支付倒计时").any(normalized::contains)
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
