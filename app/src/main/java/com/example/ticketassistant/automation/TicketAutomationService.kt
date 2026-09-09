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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TicketAutomationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var stopJob: Job? = null

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
        if (phase == PHASE_PREPARE) store.updateStatus(TaskStatus.PREPARING)
        if (phase == PHASE_SALE) {
            val gate = PersistentSubmitGate(getSharedPreferences("submit_gate", MODE_PRIVATE), "${task.date}:${task.train.trainNo}")
            if (!gate.tryAcquire()) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            store.updateStatus(TaskStatus.SEARCHING)
        }
        updateNotification(if (phase == PHASE_PREPARE) "已进入开售准备，正在唤起官方 12306" else "已到开售时间，正在官方 12306 中观察")
        OfficialAppLauncher(this).launch().onFailure { message("无法打开官方 12306：${it.message ?: "请手动打开"}") }
        if (phase == PHASE_SALE) {
            stopJob?.cancel()
            stopJob = scope.launch {
                delay(task.maxRunMinutes.coerceIn(1, 120) * 60_000L)
                store.updateStatus(TaskStatus.TAKEOVER)
                message("自动观察已结束，请接管官方 12306 完成验证码、核验和支付")
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
