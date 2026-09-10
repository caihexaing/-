package com.example.ticketassistant.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore

/**
 * 仅辅助官方 12306 的公开页面操作。验证码、登录、身份核验、风控和未知页面永远交给用户。
 */
class TicketAccessibilityService : AccessibilityService() {
    private var lastActionAt = 0L
    private var activeTaskKey: String? = null
    private var stage = Stage.SEARCH_RESULTS

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != OFFICIAL_PACKAGE) return
        val task = TaskStore(this).load() ?: return
        if (!task.enabled || task.status != TaskStatus.SEARCHING) return
        val key = "${task.date}:${task.train.trainNo}:${task.passengerName}"
        if (activeTaskKey != key) {
            activeTaskKey = key
            stage = Stage.SEARCH_RESULTS
            lastActionAt = 0L
        }
        val root = rootInActiveWindow ?: return
        val text = root.textContent()
        takeoverReason(text)?.let { reason ->
            takeover(reason)
            return
        }
        if (isPendingPaymentPage(text)) {
            TaskStore(this).updateStatus(TaskStatus.PENDING_PAYMENT)
            notify("已提交订单，请在官方 12306 待支付页面完成付款")
            stopService(android.content.Intent(this, TicketAutomationService::class.java))
            stage = Stage.DONE
            return
        }
        if (stage == Stage.DONE || System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) return

        when (stage) {
            Stage.SEARCH_RESULTS -> if (clickTrain(root, task.train.trainNo)) stage = Stage.SEAT
            Stage.SEAT -> if (clickSeatAndContinue(root, task.seat)) stage = Stage.PASSENGER
            Stage.PASSENGER -> {
                when (selectPassengerAndContinue(root, task.passengerName)) {
                    PassengerResult.SELECTED -> stage = Stage.ORDER
                    PassengerResult.AMBIGUOUS -> takeover("官方 12306 中存在多个同名乘车人，请手动选择")
                    PassengerResult.NOT_FOUND -> Unit
                }
            }
            Stage.ORDER -> if (submitOrder(root, task)) stage = Stage.SUBMITTED
            Stage.SUBMITTED, Stage.DONE -> Unit
        }
    }

    override fun onInterrupt() = Unit

    private fun clickTrain(root: AccessibilityNodeInfo, trainNo: String): Boolean {
        val node = findTextNodes(root) { value -> matchesToken(value, trainNo) }.firstOrNull() ?: return false
        val clicked = clickNodeOrParent(node)
        if (clicked) lastActionAt = System.currentTimeMillis()
        return clicked
    }

    private fun clickSeatAndContinue(root: AccessibilityNodeInfo, seat: String): Boolean {
        val seatNode = findTextNodes(root) { value -> value.trim() == seat }.firstOrNull() ?: return false
        if (!clickNodeOrParent(seatNode)) return false
        lastActionAt = System.currentTimeMillis()
        findActionNode(root, listOf("预订", "下一步", "确认"))?.let { clickNodeOrParent(it) }
        return true
    }

    private fun selectPassengerAndContinue(root: AccessibilityNodeInfo, passenger: String): PassengerResult {
        val matches = findTextNodes(root) { value -> value.trim() == passenger }
            .filter { it.childCount == 0 }
        if (matches.isEmpty()) return PassengerResult.NOT_FOUND
        if (matches.size > 1) return PassengerResult.AMBIGUOUS
        if (!clickNodeOrParent(matches.first())) return PassengerResult.NOT_FOUND
        lastActionAt = System.currentTimeMillis()
        findActionNode(root, listOf("确认", "下一步", "提交订单"))?.let { clickNodeOrParent(it) }
        return PassengerResult.SELECTED
    }

    private fun submitOrder(root: AccessibilityNodeInfo, task: com.example.ticketassistant.data.TicketTask): Boolean {
        val text = root.textContent()
        if (!matchesToken(text, task.train.trainNo) ||
            !text.contains(task.seat) ||
            !text.contains(task.passengerName)) return false
        val submit = findActionNode(root, listOf("提交订单")) ?: return false
        val gate = PersistentSubmitGate(
            getSharedPreferences("submit_gate", MODE_PRIVATE),
            "${task.date}:${task.train.trainNo}:${task.passengerName}"
        )
        if (!gate.tryAcquire()) {
            takeover("本任务已经尝试提交过订单，请到官方 12306 订单页核对结果")
            return false
        }
        val clicked = clickNodeOrParent(submit)
        if (clicked) {
            lastActionAt = System.currentTimeMillis()
            notify("已提交订单，正在等待官方 12306 显示待支付页面")
        }
        return clicked
    }

    private fun takeover(reason: String) {
        if (stage == Stage.DONE) return
        stage = Stage.DONE
        TaskStore(this).updateStatus(TaskStatus.TAKEOVER)
        stopService(android.content.Intent(this, TicketAutomationService::class.java))
        notify(reason)
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

    private fun findActionNode(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? =
        findTextNodes(root) { value -> labels.any { label -> value.trim() == label || value.trim().contains(label) } }
            .firstOrNull { it.isClickable || clickableParent(it) != null }

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

    companion object {
        const val OFFICIAL_PACKAGE = "com.MobileTicket"
        private const val CHANNEL = "ticket_takeover"
        private const val NOTIFICATION_ID = 102
        private const val ACTION_COOLDOWN_MS = 900L
    }

    private enum class Stage { SEARCH_RESULTS, SEAT, PASSENGER, ORDER, SUBMITTED, DONE }
    private enum class PassengerResult { SELECTED, AMBIGUOUS, NOT_FOUND }
}

internal fun matchesToken(text: String, token: String): Boolean =
    Regex("(^|[^A-Za-z0-9])${Regex.escape(token)}([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)

internal fun isPendingPaymentPage(text: String): Boolean {
    val normalized = text.replace(" ", "").lowercase()
    return listOf("待支付", "待付款", "订单待支付", "未支付订单", "支付倒计时").any(normalized::contains)
}

/** 普通菜单、订单说明和历史文案不能触发接管，必须是明确的安全验证/登录动作。 */
internal fun takeoverReason(text: String): String? {
    val normalized = text.lowercase()
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
