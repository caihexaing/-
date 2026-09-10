package com.example.ticketassistant.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ticketassistant.data.TaskStore

/** Restores the one active task after reboot, clock changes, zone changes, or app replacement. */
class TaskRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = TaskStore(context).load() ?: return
        if (!task.enabled || task.saleDateTime == null) return
        runCatching { TaskScheduler(context).schedule(task) }
            .onFailure { TaskStore(context).recordEvent("无法恢复开售闹钟", it.message) }
    }
}
