package com.example.ticketassistant.automation

import java.util.concurrent.atomic.AtomicBoolean

/** Once acquired, the lock remains closed even when the submit result is unknown. */
class SingleSubmitGate {
    private val submitted = AtomicBoolean(false)
    fun tryAcquire(): Boolean = submitted.compareAndSet(false, true)
    fun isLocked(): Boolean = submitted.get()
}

/** Process-restart-safe gate used by the real task runner. */
class PersistentSubmitGate(private val preferences: android.content.SharedPreferences, private val key: String) {
    @Synchronized
    fun tryAcquire(): Boolean {
        if (preferences.getBoolean(key, false)) return false
        return preferences.edit().putBoolean(key, true).commit()
    }

    fun isLocked(): Boolean = preferences.getBoolean(key, false)

    /** Only used when ACTION_CLICK was explicitly rejected by the accessibility framework. */
    @Synchronized
    fun releaseAfterClickRejected() {
        preferences.edit().remove(key).commit()
    }
}

fun submitGateKey(task: com.example.ticketassistant.data.TicketTask): String =
    "${task.taskId}:${task.saleDateTime}:${task.date}:${task.train.trainNo}"
