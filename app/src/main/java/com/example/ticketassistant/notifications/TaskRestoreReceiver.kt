package com.example.ticketassistant.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.ticketassistant.MainActivity
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.SaleState
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskTiming

/** Restores the one active task after reboot, clock changes, zone changes, or app replacement. */
class TaskRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = TaskStore(context).load() ?: return
        if (!task.enabled || task.status in setOf(TaskStatus.DISABLED, TaskStatus.EXPIRED, TaskStatus.RESULT_UNKNOWN, TaskStatus.PENDING_PAYMENT, TaskStatus.SUBMIT_REJECTED)) return
        if (task.status in setOf(TaskStatus.SUBMIT_ACTION_SENT, TaskStatus.WAITING_SERVER_RESULT)) {
            TaskStore(context).recordEvent("系统恢复完成：提交结果待人工核对，禁止自动重试")
            notify(context, task.taskId, "提交结果待核对，请打开官方 12306 订单页确认", startExecution = false)
            return
        }
        if (task.status in setOf(
                TaskStatus.WAITING_OFFICIAL_PAGE,
                TaskStatus.VALIDATING_SEARCH_RESULT,
                TaskStatus.SELECTING_TRAIN_SEAT,
                TaskStatus.SELECTING_PASSENGER,
                TaskStatus.VALIDATING_ORDER,
                TaskStatus.OBSERVING,
                TaskStatus.SEARCHING
            )) {
            TaskStore(context).recordEvent("系统恢复完成：官方页面执行状态待核对，禁止自动重启点击")
            notify(context, task.taskId, "官方页面执行状态待核对，请打开行程助手查看诊断", startExecution = false)
            return
        }
        if (task.saleState == SaleState.ALREADY_ON_SALE) {
            TaskStore(context).recordEvent("系统恢复完成：已开售任务等待你点击通知启动")
            notify(context, task.taskId, "目标车次已开售，点击通知开始执行", startExecution = true)
            return
        }
        if (task.saleState != SaleState.NOT_YET_ON_SALE || task.saleDateTime == null) return
        if (!TaskTiming.isFutureSaleDateTime(task.saleDateTime)) {
            TaskStore(context).save(task.copy(
                saleState = SaleState.UNKNOWN,
                enabled = false,
                status = TaskStatus.DRAFT,
                lastError = "开售时间已过，请重新查询确认开售状态"
            ))
            notify(context, task.taskId, "开售时间已过，请打开行程助手重新查询", startExecution = false)
            return
        }
        runCatching { TaskScheduler(context).schedule(task) }
            .onSuccess { notify(context, task.taskId, "开售闹钟已恢复：${task.saleDateTime}", startExecution = false) }
            .onFailure {
                TaskStore(context).recordEvent("无法恢复开售闹钟", it.message)
                notify(context, task.taskId, "开售闹钟恢复失败，请打开行程助手检查设置", startExecution = false)
            }
    }

    private fun notify(context: Context, taskId: String, message: String, startExecution: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务恢复", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RESUME_TASK, startExecution)
            .putExtra(MainActivity.EXTRA_TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("行程助手")
            .setContentText(message)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    private companion object {
        const val CHANNEL = "ticket_restore"
        const val NOTIFICATION_ID = 103
    }
}
