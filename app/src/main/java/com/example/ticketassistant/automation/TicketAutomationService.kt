package com.example.ticketassistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ticketassistant.R
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
    private var stopJob: Job? = null
    private var searchJob: Job? = null
    private var resultJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "抢票执行", NotificationManager.IMPORTANCE_HIGH))
        startForeground(NOTIFICATION_ID, notification("正在准备行程执行"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phase = intent?.getStringExtra(EXTRA_PHASE) ?: PHASE_SALE
        val task = TaskStore(this).load()
        if (task == null || !task.enabled) {
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
            return START_NOT_STICKY
        }

        store.recordEvent("开售阶段：开始查询目标车次")
        updateNotification("已到开售时间，正在查询目标车次")
        searchJob?.cancel()
        stopJob?.cancel()
        searchJob = scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + task.maxRunMinutes.coerceIn(1, 120) * 60_000L
            var firstAttempt = true
            while (isActive && System.currentTimeMillis() < deadline) {
                val queryResult = runCatching {
                    TrainRepository().query(task.date, task.from, task.to)
                }
                val matched = queryResult.getOrNull()?.firstOrNull { it.matchesTask(task) }
                queryResult.exceptionOrNull()?.let { failure ->
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
                        resultJob?.cancel()
                        resultJob = scope.launch {
                            delay(90_000L)
                            val current = store.load()
                            if (current?.taskId == task.taskId && current.status == TaskStatus.OBSERVING) {
                                store.updateStatus(TaskStatus.RESULT_UNKNOWN, "订单提交结果未能确认，请到官方订单页核对")
                                message("未能确认订单结果，请到官方 12306 的订单页核对；不会自动重试提交")
                                stopSelf()
                            }
                        }
                    }
                    return@launch
                }
                launch(Dispatchers.Main) {
                    store.recordEvent(if (firstAttempt) "正在查询目标车次 ${task.train.trainNo}" else "暂未发现目标车次，继续查询")
                    updateNotification(if (firstAttempt) "正在查询目标车次 ${task.train.trainNo}" else "暂未发现目标车次，继续查询")
                }
                val nextDelay = if (firstAttempt) 3_000L else 8_000L
                firstAttempt = false
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

    private fun com.example.ticketassistant.data.Train.matchesTask(task: com.example.ticketassistant.data.TicketTask): Boolean {
        val seatValue = seats[task.seat].orEmpty().trim()
        return trainNo.equals(task.train.trainNo, ignoreCase = true) &&
            from == task.from.name && to == task.to.name &&
            depart == task.train.depart && arrive == task.train.arrive &&
            seatValue.isNotBlank() && seatValue !in setOf("无", "无票", "--", "*")
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
        stopJob?.cancel()
        searchJob?.cancel()
        resultJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_PHASE = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_SALE = "sale"
        private const val CHANNEL = "ticket_execution"
        private const val NOTIFICATION_ID = 110
    }
}
