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
            put("fromCity", task.from.city); put("fromPinyin", task.from.pinyin)
            put("toName", task.to.name); put("toCode", task.to.telecode); put("toCity", task.to.city); put("toPinyin", task.to.pinyin)
            put("trainNo", task.train.trainNo)
            put("depart", task.train.depart); put("arrive", task.train.arrive); put("duration", task.train.duration)
            put("seatMap", JSONObject(task.train.seats))
            put("seat", task.seat); put("passenger", crypto.encrypt(task.passengerName)); put("saleTime", task.saleTime)
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
        val train = Train(j.getString("trainNo"), j.getString("fromName"), j.getString("toName"), j.getString("depart"), j.getString("arrive"), j.optString("duration"), seatMap)
        TicketTask(j.getString("date"), Station(j.getString("fromName"), j.getString("fromCode"), j.optString("fromCity"), j.optString("fromPinyin")), Station(j.getString("toName"), j.getString("toCode"), j.optString("toCity"), j.optString("toPinyin")), train, j.getString("seat"), crypto.decrypt(j.getString("passenger")), j.getString("saleTime"), enabled = j.optBoolean("enabled"), status = TaskStatus.valueOf(j.optString("status", TaskStatus.ENABLED.name)), lastEvent = j.optString("lastEvent").ifBlank { null }, lastEventAt = j.optLong("lastEventAt").takeIf { it > 0L }, lastError = j.optString("lastError").ifBlank { null })
    }.getOrNull()

    fun updateStatus(status: TaskStatus, event: String? = null, error: String? = null) {
        val task = load() ?: return
        save(task.copy(
            status = status,
            enabled = status != TaskStatus.DISABLED && status != TaskStatus.EXPIRED,
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
