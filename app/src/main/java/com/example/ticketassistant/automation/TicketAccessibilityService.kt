package com.example.ticketassistant.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.view.accessibility.AccessibilityEvent
import com.example.ticketassistant.R
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore

/**
 * 首版仅观察官方 12306 页面是否可识别。验证码、登录、核验、候补、支付以及任何未知页面都必须由用户处理。
 */
class TicketAccessibilityService : AccessibilityService() {
    private var lastSignalAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != OFFICIAL_PACKAGE) return
        val root = rootInActiveWindow ?: return
        val text = root.textContent().lowercase()
        val takeoverReason = when {
            listOf("验证码", "滑块", "图形验证", "安全验证").any(text::contains) -> "官方 12306 要求验证码，请手动完成"
            listOf("登录", "短信验证", "身份核验", "实名认证").any(text::contains) -> "官方 12306 需要登录或身份核验，请手动完成"
            listOf("支付", "订单确认", "候补协议").any(text::contains) -> "已进入订单确认、候补或支付环节，请手动接管"
            else -> null
        }
        if (takeoverReason != null && System.currentTimeMillis() - lastSignalAt > 10_000L) {
            lastSignalAt = System.currentTimeMillis()
            TaskStore(this).updateStatus(TaskStatus.TAKEOVER)
            applicationContext.stopService(android.content.Intent(applicationContext, TicketAutomationService::class.java))
            notifyTakeover(takeoverReason)
        }
    }

    override fun onInterrupt() = Unit

    private fun notifyTakeover(reason: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "人工接管", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("行程助手需要你接管")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build())
    }

    private fun android.view.accessibility.AccessibilityNodeInfo.textContent(): String = buildString {
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
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
    }
}
