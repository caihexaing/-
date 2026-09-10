package com.example.ticketassistant.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TaskTiming
import com.example.ticketassistant.automation.TicketAutomationService
import androidx.core.content.ContextCompat

class TaskScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(task: TicketTask) {
        val sale = task.saleDateTime ?: throw IllegalArgumentException("开售日期时间未知，请填写 yyyy-MM-dd HH:mm")
        val target = requireNotNull(TaskTiming.parseSaleDateTime(sale)) { "开售日期时间格式应为 yyyy-MM-dd HH:mm" }.toEpochMilli()
        cancel()
        if (target <= System.currentTimeMillis()) {
            TaskStore(context).updateStatus(com.example.ticketassistant.data.TaskStatus.PREPARING, "开售时间已到，立即开始执行")
            ContextCompat.startForegroundService(context, Intent(context, TicketAutomationService::class.java).putExtra(TicketAutomationService.EXTRA_PHASE, TicketAutomationService.PHASE_SALE))
            return
        }
        TaskStore(context).updateStatus(com.example.ticketassistant.data.TaskStatus.WAITING_FOR_SALE, "等待开售：$sale")
        scheduleAt(target - 10 * 60_000L, "reminder", 1, task.taskId)
        scheduleAt(target - 30_000L, "prepare", 2, task.taskId)
        scheduleAt(target, "sale", 3, task.taskId)
    }

    fun cancel() = listOf(1, 2, 3).forEach { request(it, "").also(alarms::cancel) }

    private fun scheduleAt(time: Long, phase: String, id: Int, taskId: String) {
        if (time <= System.currentTimeMillis()) return
        val pending = request(id, phase, taskId)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        } else {
            // The task remains usable, but this fallback is intentionally not advertised as exact.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }

    private fun request(id: Int, phase: String, taskId: String = ""): PendingIntent = PendingIntent.getBroadcast(context, id,
        Intent(context, TaskAlarmReceiver::class.java)
            .putExtra(TaskAlarmReceiver.EXTRA_PHASE, phase)
            .putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

}
