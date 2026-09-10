package com.example.ticketassistant.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ticketassistant.R
import com.example.ticketassistant.automation.TicketAutomationService
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = TaskStore(context)
        val task = store.load() ?: return
        val expectedTaskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (!task.enabled || (expectedTaskId != null && expectedTaskId.isNotBlank() && expectedTaskId != task.taskId)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "抢票提醒", NotificationManager.IMPORTANCE_HIGH))
        val phase = intent.getStringExtra(EXTRA_PHASE) ?: "开售"
        store.recordEvent("闹钟已触发：$phase")
        if (phase == TicketAutomationService.PHASE_PREPARE || phase == TicketAutomationService.PHASE_SALE) {
            if (phase == TicketAutomationService.PHASE_SALE && task.status !in setOf(TaskStatus.WAITING_FOR_SALE, TaskStatus.ENABLED, TaskStatus.PREPARING)) return
            val runner = Intent(context, TicketAutomationService::class.java).putExtra(TicketAutomationService.EXTRA_PHASE, phase)
            ContextCompat.startForegroundService(context, runner)
            return
        }
        val message = when (phase) {
            "prepare" -> "距离开售 30 秒，请保持手机解锁并确认官方 12306 已登录。"
            "reminder" -> "距离开售 10 分钟，请检查网络、通知和无障碍服务。"
            else -> "已到开售时间。请在官方 12306 中完成验证码、核验和支付。"
        }
        manager.notify(100, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("行程助手：$phase")
            .setContentText(message).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
    }

    companion object {
        const val CHANNEL = "ticket_reminders"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_TASK_ID = "task_id"
    }
}
