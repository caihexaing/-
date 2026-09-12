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
import kotlinx.coroutines.withContext

class TicketAutomationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var probeJob: Job? = null
    private var queryRepository: TrainRepository? = null
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
        when (phase) {
            PHASE_PREPARE -> {
                if (!AccessibilityServiceStatus.isEnabled(this)) {
                    store.updateStatus(TaskStatus.TAKEOVER, "无障碍服务未启用，无法准备官方页面")
                    message("无障碍服务未启用，无法进行开售前预热；请先启用服务")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                store.updateStatus(TaskStatus.PREPARING, "已触发开售前准备")
                store.recordEvent("准备阶段：正在预热只读查询会话并唤起官方 12306")
                updateNotification("已进入开售准备，正在预热官方页面")
                startSessionWarmup(task, store)
                if (!TicketAccessibilityService.hasRecentOfficialWindow(this)) {
                    OfficialAppLauncher(this).launch().onFailure {
                        store.updateStatus(TaskStatus.TAKEOVER, "官方 12306 打开失败", it.message)
                        message("无法打开官方 12306：${it.message ?: "请手动打开"}")
                        stopSelf()
                    }
                } else {
                    store.recordEvent("准备阶段检测到官方 12306 已在前台，保留当前页面")
                }
            }
            PHASE_SALE -> {
                if (!AccessibilityServiceStatus.isEnabled(this)) {
                    store.updateStatus(TaskStatus.TAKEOVER, "开售时无障碍服务未启用，尚未操作官方页面")
                    message("开售时无障碍服务未启用，尚未操作官方页面")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                store.recordSaleT0("已到开售时刻（SALE_T0），开始官方 App 冷启动导航")
                store.recordColdStartState(TaskStatus.COLD_START, "冷启动阶段已开始：检查官方 12306 前台窗口")
                store.recordEvent("开售阶段：旁路查询已启动，官方页面操作不等待查询结果")
                updateNotification("已到开售时间，正在准备官方 12306 页面")
                startSideChannelProbe(task, store)
                if (!TicketAccessibilityService.hasRecentOfficialWindow(this)) {
                    store.recordColdStartAttempt("未检测到官方 12306 前台窗口，正在尝试启动官方 App")
                    OfficialAppLauncher(this).launch().onFailure {
                        store.recordColdStartState(TaskStatus.COLD_START, "官方 12306 冷启动失败，等待人工处理", it.message)
                        store.updateStatus(TaskStatus.TAKEOVER, "官方 12306 启动失败，请手动打开官方 App", it.message)
                        message("无法启动官方 12306：${it.message ?: "请手动打开后重新开始"}")
                        stopSelf()
                    }
                } else {
                    store.recordEvent("已检测到官方 12306 前台窗口，继续读取当前页面")
                }
                TicketAccessibilityService.signalSaleT0(this, task.taskId)
            }
            else -> {
                store.recordEvent("未知执行阶段：$phase")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSessionWarmup(task: com.example.ticketassistant.data.TicketTask, store: TaskStore) {
        probeJob?.cancel()
        val repository = queryRepository ?: TrainRepository().also { queryRepository = it }
        probeJob = scope.launch(Dispatchers.IO) {
            val warmup = runCatching { repository.warmUpSession() }
            withContext(Dispatchers.Main) {
                warmup.fold(
                    onSuccess = { warmed ->
                        store.recordEvent(
                            if (warmed) {
                                "准备阶段：只读查询会话已预热（${task.from.name} → ${task.to.name}）"
                            } else {
                                "准备阶段：只读查询会话未返回 Cookie，官方页面预热仍继续"
                            }
                        )
                    },
                    onFailure = { error ->
                        store.recordEvent("准备阶段：只读查询会话预热失败，官方页面预热仍继续", error.message)
                    }
                )
            }
        }
    }

    /** Query availability as a bounded diagnostic side channel; it never drives UI actions. */
    private fun startSideChannelProbe(task: com.example.ticketassistant.data.TicketTask, store: TaskStore) {
        probeJob?.cancel()
        val repository = queryRepository ?: TrainRepository().also { queryRepository = it }
        probeJob = scope.launch(Dispatchers.IO) {
            val maximumRunMillis = task.maxRunMinutes.coerceIn(1, 120) * 60_000L
            val deadline = System.currentTimeMillis() + maximumRunMillis
            val polling = PollingPolicy(maximumRunMillis = maximumRunMillis)
            val startedAt = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() < deadline) {
                val queryResult = runCatching { repository.query(task.date, task.from, task.to) }
                val failure = queryResult.exceptionOrNull()
                if (failure != null) {
                    withContext(Dispatchers.Main) {
                        store.recordEvent(
                            "旁路查询失败（不影响官方页面操作）：${failure.message ?: failure.javaClass.simpleName}",
                            failure.message
                        )
                        if (isRateLimited(failure)) {
                            store.recordEvent("旁路查询触发限流，已停止旁路查询；不影响官方页面快速路径")
                        }
                    }
                    if (isRateLimited(failure)) break
                } else {
                    val routeMatch = queryResult.getOrThrow().firstOrNull { it.matchesTrain(task) }
                    val matched = routeMatch?.takeIf { it.hasPurchasableSeat(task.seat) }
                    val event = when {
                        matched != null -> "旁路查询发现目标车次和${task.seat}可购买（仅诊断，不驱动页面操作）"
                        routeMatch != null -> "旁路查询发现目标车次，但${task.seat}暂不可购买（仅诊断）"
                        else -> "旁路查询暂未发现目标车次 ${task.train.trainNo}（仅诊断）"
                    }
                    withContext(Dispatchers.Main) { store.recordEvent(event) }
                }
                val nextDelay = polling.nextDelayMillis(System.currentTimeMillis() - startedAt) ?: break
                delay(nextDelay)
            }
            withContext(Dispatchers.Main) {
                store.recordEvent("旁路查询结束；官方页面状态仍由无障碍流程决定")
            }
        }
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
        probeJob?.cancel()
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
