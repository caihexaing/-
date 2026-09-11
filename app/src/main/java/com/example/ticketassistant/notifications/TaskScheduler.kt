package com.example.ticketassistant.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TaskTiming
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.SaleState
import com.example.ticketassistant.automation.TicketAutomationService
import androidx.core.content.ContextCompat

class TaskScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(task: TicketTask) {
        cancel()
        when (task.saleState) {
            SaleState.ALREADY_ON_SALE -> {
                TaskStore(context).updateStatus(TaskStatus.PREPARING, "已确认目标席别有票，立即开始执行")
                startImmediately(task)
            }
            SaleState.NOT_YET_ON_SALE -> {
                val sale = task.saleDateTime ?: throw IllegalArgumentException("未开售任务必须填写完整开售日期时间")
                val target = requireNotNull(TaskTiming.parseSaleDateTime(sale)) { "开售日期时间格式应为 yyyy-MM-dd HH:mm" }.toEpochMilli()
                require(target > System.currentTimeMillis()) { "开售日期时间必须晚于当前时间" }
                if (!canScheduleExactAlarms()) {
                    TaskStore(context).save(task.copy(enabled = false, status = TaskStatus.DRAFT, lastError = "未授予精确闹钟权限，任务未启用"))
                    throw IllegalStateException("请先允许精确闹钟，避免开售调度延迟")
                }
                TaskStore(context).updateStatus(TaskStatus.WAITING_FOR_SALE, "等待开售：$sale")
                scheduleAt(target - 10 * 60_000L, "reminder", 1, task.taskId)
                scheduleAt(target - 30_000L, "prepare", 2, task.taskId)
                scheduleAt(target, "sale", 3, task.taskId)
            }
            SaleState.UNKNOWN -> throw IllegalArgumentException("开售状态未知，请重新查询确认后再启用任务")
        }
    }

    fun cancel() = listOf(1, 2, 3).forEach { request(it, "").also(alarms::cancel) }

    fun startImmediately(task: TicketTask) {
        require(task.enabled) { "任务未启用" }
        require(task.saleState == SaleState.ALREADY_ON_SALE) { "只有已确认开售的任务可以立即执行" }
        TaskStore(context).updateStatus(TaskStatus.PREPARING, "用户已确认，立即开始执行")
        startSaleService(task)
    }

    private fun scheduleAt(time: Long, phase: String, id: Int, taskId: String) {
        if (time <= System.currentTimeMillis()) return
        val pending = request(id, phase, taskId)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
    }

    private fun canScheduleExactAlarms(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun startSaleService(task: TicketTask) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TicketAutomationService::class.java)
                .putExtra(TicketAutomationService.EXTRA_PHASE, TicketAutomationService.PHASE_SALE)
                .putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, task.taskId)
        )
    }

    private fun request(id: Int, phase: String, taskId: String = ""): PendingIntent = PendingIntent.getBroadcast(context, id,
        Intent(context, TaskAlarmReceiver::class.java)
            .putExtra(TaskAlarmReceiver.EXTRA_PHASE, phase)
            .putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

}
