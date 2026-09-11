package com.example.ticketassistant.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("task_store", Context.MODE_PRIVATE)
    private val crypto = NameCrypto()

    fun save(task: TicketTask) {
        val json = JSONObject().apply {
            put("date", task.date); put("fromName", task.from.name); put("fromCode", task.from.telecode)
            put("taskId", task.taskId)
            put("fromCity", task.from.city); put("fromPinyin", task.from.pinyin)
            put("toName", task.to.name); put("toCode", task.to.telecode); put("toCity", task.to.city); put("toPinyin", task.to.pinyin)
            put("trainNo", task.train.trainNo)
            put("depart", task.train.depart); put("arrive", task.train.arrive); put("duration", task.train.duration)
            put("seatMap", JSONObject(task.train.seats))
            put("seat", task.seat); put("passenger", crypto.encrypt(task.passengerName))
            put("saleState", task.saleState.name)
            task.saleDateTime?.let { put("saleDateTime", it) }
            put("saleTimeSource", task.saleTimeSource.name)
            task.train.saleTime?.let { put("trainSaleTime", it) }
            put("enabled", task.enabled); put("status", task.status.name)
            task.lastEvent?.let { put("lastEvent", it) }
            task.lastEventAt?.let { put("lastEventAt", it) }
            task.lastError?.let { put("lastError", it) }
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun load(): TicketTask? = runCatching {
        val j = JSONObject(prefs.getString(KEY, null) ?: return null)
        val seatMap = buildMap { val obj = j.optJSONObject("seatMap") ?: JSONObject(); obj.keys().forEach { key -> put(key, obj.optString(key)) } }
        val train = Train(j.getString("trainNo"), j.getString("fromName"), j.getString("toName"), j.getString("depart"), j.getString("arrive"), j.optString("duration"), seatMap, j.optString("trainSaleTime").ifBlank { null })
        val saleDateTime = j.optString("saleDateTime").ifBlank { null }
        val hasSaleState = j.has("saleState")
        val saleState = runCatching { SaleState.valueOf(j.optString("saleState")) }
            .getOrDefault(if (hasSaleState) SaleState.UNKNOWN else SaleState.UNKNOWN)
        val source = runCatching { SaleTimeSource.valueOf(j.optString("saleTimeSource", if (saleDateTime == null) SaleTimeSource.UNKNOWN.name else SaleTimeSource.USER_CONFIRMED.name)) }
            .getOrDefault(SaleTimeSource.UNKNOWN)
        val storedStatus = runCatching { TaskStatus.valueOf(j.optString("status", TaskStatus.ENABLED.name)) }.getOrDefault(TaskStatus.ENABLED)
        val invalidSaleData = !hasSaleState || saleState == SaleState.UNKNOWN ||
            (saleState == SaleState.NOT_YET_ON_SALE &&
                (saleDateTime == null || TaskTiming.parseSaleDateTime(saleDateTime) == null))
        val status = if (invalidSaleData && storedStatus in setOf(
                TaskStatus.ENABLED, TaskStatus.WAITING_FOR_SALE, TaskStatus.PREPARING, TaskStatus.OBSERVING, TaskStatus.SEARCHING
            )) TaskStatus.DRAFT else storedStatus
        val lastError = j.optString("lastError").ifBlank { null }
        TicketTask(
            taskId = j.optString("taskId").ifBlank { java.util.UUID.randomUUID().toString() },
            date = j.getString("date"),
            from = Station(j.getString("fromName"), j.getString("fromCode"), j.optString("fromCity"), j.optString("fromPinyin")),
            to = Station(j.getString("toName"), j.getString("toCode"), j.optString("toCity"), j.optString("toPinyin")),
            train = train,
            seat = j.getString("seat"),
            passengerName = crypto.decrypt(j.getString("passenger")),
            saleState = saleState,
            saleDateTime = saleDateTime,
            saleTimeSource = if (hasSaleState) source else SaleTimeSource.UNKNOWN,
            enabled = !invalidSaleData &&
                (saleState != SaleState.NOT_YET_ON_SALE || saleDateTime != null) &&
                j.optBoolean("enabled", false),
            status = status,
            lastEvent = j.optString("lastEvent").ifBlank { null },
            lastEventAt = j.optLong("lastEventAt").takeIf { it > 0L },
            lastError = if (invalidSaleData) {
                lastError ?: "开售状态未知，请重新查询确认"
            } else lastError
        )
    }.getOrNull()

    fun updateStatus(status: TaskStatus, event: String? = null, error: String? = null) {
        val task = load() ?: return
        save(task.copy(
            status = status,
            enabled = status !in setOf(TaskStatus.DRAFT, TaskStatus.DISABLED, TaskStatus.EXPIRED, TaskStatus.TAKEOVER, TaskStatus.RESULT_UNKNOWN, TaskStatus.PENDING_PAYMENT) &&
                task.saleState != SaleState.UNKNOWN,
            lastEvent = event ?: task.lastEvent,
            lastEventAt = if (event != null) System.currentTimeMillis() else task.lastEventAt,
            lastError = error
        ))
    }

    fun recordEvent(event: String, error: String? = null) {
        val task = load() ?: return
        save(task.copy(lastEvent = event, lastEventAt = System.currentTimeMillis(), lastError = error))
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object { const val KEY = "active_task" }
}

private class NameCrypto {
    private val alias = "ticket_assistant_name"
    private val key: SecretKey by lazy {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generator.generateKey()
        }
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
}
