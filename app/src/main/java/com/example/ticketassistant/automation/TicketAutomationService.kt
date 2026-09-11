package com.example.ticketassistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TicketAutomationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null
    private var foregroundStartFailed = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "抢票执行", NotificationManager.IMPORTANCE_HIGH))
        runCatching { startForeground(NOTIFICATION_ID, notification("正在准备行程执行")) }
            .onFailure {
                foregroundStartFailed = true
                TaskStore(this).recordEvent("系统阻止前台执行服务启动", it.message)
                stopSelf()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundStartFailed) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val phase = intent?.getStringExtra(EXTRA_PHASE) ?: PHASE_SALE
        val task = TaskStore(this).load()
        val expectedTaskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (task == null || !task.enabled || (expectedTaskId != null && expectedTaskId != task.taskId)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val store = TaskStore(this)
        if (phase == PHASE_PREPARE) store.updateStatus(TaskStatus.PREPARING, "已触发开售前准备")
        if (phase == PHASE_SALE && task.status in setOf(TaskStatus.ENABLED, TaskStatus.WAITING_FOR_SALE, TaskStatus.PREPARING)) store.updateStatus(TaskStatus.PREPARING, "已触发开售查询")
        if (phase == PHASE_PREPARE) {
            store.recordEvent("准备阶段：正在唤起官方 12306")
            updateNotification("已进入开售准备，正在唤起官方 12306")
            OfficialAppLauncher(this).launch().onFailure {
                store.updateStatus(TaskStatus.TAKEOVER, "官方 12306 打开失败", it.message)
                message("无法打开官方 12306：${it.message ?: "请手动打开"}")
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        store.recordEvent("开售阶段：开始查询目标车次")
        updateNotification("已到开售时间，正在查询目标车次")
        searchJob?.cancel()
        searchJob = scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + task.maxRunMinutes.coerceIn(1, 120) * 60_000L
            val repository = TrainRepository()
            val polling = PollingPolicy(maximumRunMillis = task.maxRunMinutes.coerceIn(1, 120) * 60_000L)
            val startedAt = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() < deadline) {
                val queryResult = runCatching {
                    repository.query(task.date, task.from, task.to)
                }
                val routeMatch = queryResult.getOrNull()?.firstOrNull { it.matchesTrain(task) }
                val matched = routeMatch?.takeIf { it.hasPurchasableSeat(task.seat) }
                val failure = queryResult.exceptionOrNull()
                if (failure != null && isRateLimited(failure)) {
                    launch(Dispatchers.Main) {
                        store.updateStatus(TaskStatus.TAKEOVER, "官方接口触发限流，已停止自动查询", failure.message)
                        message("官方接口触发限流，任务已停止；请稍后手动重试")
                        stopSelf()
                    }
                    return@launch
                }
                failure?.let { failure ->
                    launch(Dispatchers.Main) {
                        store.updateStatus(TaskStatus.SEARCHING, "查询失败，稍后重试", failure.message ?: failure.javaClass.simpleName)
                    }
                }
                if (matched != null) {
                    launch(Dispatchers.Main) {
                        store.updateStatus(TaskStatus.OBSERVING, "已找到目标车次：${task.train.trainNo}")
                        updateNotification("已找到 ${task.train.trainNo}，正在打开官方 12306 辅助下单")
                        OfficialAppLauncher(this@TicketAutomationService).launch().onFailure {
                            store.updateStatus(TaskStatus.TAKEOVER, "官方 12306 打开失败", it.message)
                            message("无法打开官方 12306：${it.message ?: "请手动打开"}")
                        }
                        // 结果确认交给无障碍服务和用户，不使用固定超时打断人工接管。
                        stopSelf()
                    }
                    return@launch
                }
                if (routeMatch != null) {
                    launch(Dispatchers.Main) {
                        val message = "已找到 ${task.train.trainNo}，但所选席别 ${task.seat} 暂无可购买余票，继续查询"
                        store.updateStatus(TaskStatus.SEARCHING, message)
                        updateNotification(message)
                    }
                }
                launch(Dispatchers.Main) {
                    val message = if (routeMatch == null) {
                        "暂未发现目标车次 ${task.train.trainNo}，按 15-30 秒策略继续查询"
                    } else {
                        "目标车次仍无可购买的 ${task.seat}，按 15-30 秒策略继续查询"
                    }
                    store.recordEvent(message)
                    updateNotification(message)
                }
                val nextDelay = polling.nextDelayMillis(System.currentTimeMillis() - startedAt) ?: break
                delay(nextDelay)
            }
            launch(Dispatchers.Main) {
                store.updateStatus(TaskStatus.EXPIRED, "查询超时，未找到目标车次")
                message("在限定时间内未找到目标车次，任务已结束")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun com.example.ticketassistant.data.Train.matchesTrain(task: com.example.ticketassistant.data.TicketTask): Boolean =
        trainNo.equals(task.train.trainNo, ignoreCase = true) &&
            from == task.from.name && to == task.to.name &&
            depart == task.train.depart && arrive == task.train.arrive

    private fun com.example.ticketassistant.data.Train.hasPurchasableSeat(seat: String): Boolean {
        val value = seats[seat]?.trim().orEmpty()
        return value.isNotBlank() && value !in TrainRepository.UNAVAILABLE_SEAT_VALUES
    }

    private fun isRateLimited(failure: Throwable): Boolean {
        val text = generateSequence(failure) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return listOf("429", "too many", "频繁", "限流", "风控", "access denied", "403").any(text::contains)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun message(text: String) {
        updateNotification(text)
        getSystemService(NotificationManager::class.java).notify(101, notification(text))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("行程助手")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    override fun onDestroy() {
        searchJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_PHASE = "phase"
        const val EXTRA_TASK_ID = "task_id"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_SALE = "sale"
        private const val CHANNEL = "ticket_execution"
        private const val NOTIFICATION_ID = 110
    }
}
