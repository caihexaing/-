package com.example.ticketassistant.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.ticketassistant.data.TicketTask
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

class TaskScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(task: TicketTask) {
        val target = LocalDateTime.parse("${task.date} ${task.saleTime}", DATE_TIME).atZone(CHINA_ZONE).toInstant().toEpochMilli()
        require(target > System.currentTimeMillis()) { "开售时间已过去" }
        scheduleAt(target - 10 * 60_000L, "reminder", 1)
        scheduleAt(target - 30_000L, "prepare", 2)
        scheduleAt(target, "sale", 3)
    }

    fun cancel() = listOf(1, 2, 3).forEach { request(it, "").also(alarms::cancel) }

    private fun scheduleAt(time: Long, phase: String, id: Int) {
        if (time <= System.currentTimeMillis()) return
        val pending = request(id, phase)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        } else {
            // The task remains usable, but this fallback is intentionally not advertised as exact.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }

    private fun request(id: Int, phase: String): PendingIntent = PendingIntent.getBroadcast(context, id,
        Intent(context, TaskAlarmReceiver::class.java).putExtra(TaskAlarmReceiver.EXTRA_PHASE, phase),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private companion object {
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
