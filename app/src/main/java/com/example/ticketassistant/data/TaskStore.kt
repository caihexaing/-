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
            task.lastPageState?.let { put("lastPageState", it) }
            task.lastAction?.let { put("lastAction", it) }
            task.lastAutomationStage?.let { put("lastAutomationStage", it) }
            task.lastRootPackage?.let { put("lastRootPackage", it) }
            task.lastEvidenceSource?.let { put("lastEvidenceSource", it) }
            task.lastSearchContextStatus?.let { put("lastSearchContextStatus", it) }
            task.lastMissingEvidence?.let { put("lastMissingEvidence", it) }
            task.lastSearchContextAt?.let { put("lastSearchContextAt", it) }
            task.lastSearchSnapshotFingerprint?.let { put("lastSearchSnapshotFingerprint", it) }
            task.lastSaleT0At?.let { put("lastSaleT0At", it) }
            task.coldStartAt?.let { put("coldStartAt", it) }
            put("coldStartAttempts", task.coldStartAttempts)
            task.coldStartLastFailure?.let { put("coldStartLastFailure", it) }
            task.lastWindowChangedAt?.let { put("lastWindowChangedAt", it) }
            task.lastAccessibilityEventAt?.let { put("lastAccessibilityEventAt", it) }
            put("accessibilityEventCount", task.accessibilityEventCount)
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
                TaskStatus.ENABLED, TaskStatus.WAITING_FOR_SALE, TaskStatus.PREPARING,
                TaskStatus.SALE_T0,
                TaskStatus.COLD_START, TaskStatus.OPENING_OFFICIAL_APP,
                TaskStatus.OPENING_HOME, TaskStatus.FILLING_SEARCH_FORM,
                TaskStatus.WAITING_SEARCH_RESULT,
                TaskStatus.WAITING_OFFICIAL_PAGE, TaskStatus.OPENING_SEARCH,
                TaskStatus.FILLING_DEPARTURE, TaskStatus.FILLING_ARRIVAL,
                TaskStatus.FILLING_DATE, TaskStatus.SUBMITTING_SEARCH,
                TaskStatus.VALIDATING_SEARCH_RESULT,
                TaskStatus.SELECTING_TRAIN_SEAT, TaskStatus.SELECTING_PASSENGER,
                TaskStatus.VALIDATING_ORDER, TaskStatus.SUBMIT_ACTION_SENT,
                TaskStatus.WAITING_SERVER_RESULT, TaskStatus.OBSERVING, TaskStatus.SEARCHING
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
            } else lastError,
            lastPageState = j.optString("lastPageState").ifBlank { null },
            lastAction = j.optString("lastAction").ifBlank { null },
            lastAutomationStage = j.optString("lastAutomationStage").ifBlank { null },
            lastRootPackage = j.optString("lastRootPackage").ifBlank { null },
            lastEvidenceSource = j.optString("lastEvidenceSource").ifBlank { null },
            lastSearchContextStatus = j.optString("lastSearchContextStatus").ifBlank { null },
            lastMissingEvidence = j.optString("lastMissingEvidence").ifBlank { null },
            lastSearchContextAt = j.optLong("lastSearchContextAt").takeIf { it > 0L },
            lastSearchSnapshotFingerprint = j.optString("lastSearchSnapshotFingerprint").ifBlank { null },
            lastSaleT0At = j.optLong("lastSaleT0At").takeIf { it > 0L },
            coldStartAt = j.optLong("coldStartAt").takeIf { it > 0L },
            coldStartAttempts = j.optInt("coldStartAttempts", 0).coerceAtLeast(0),
            coldStartLastFailure = j.optString("coldStartLastFailure").ifBlank { null },
            lastWindowChangedAt = j.optLong("lastWindowChangedAt").takeIf { it > 0L },
            lastAccessibilityEventAt = j.optLong("lastAccessibilityEventAt").takeIf { it > 0L },
            accessibilityEventCount = j.optInt("accessibilityEventCount", 0).coerceAtLeast(0)
        )
    }.getOrNull()

    fun updateStatus(status: TaskStatus, event: String? = null, error: String? = null) {
        val task = load() ?: return
        save(task.copy(
            status = status,
            enabled = status !in setOf(TaskStatus.DRAFT, TaskStatus.DISABLED, TaskStatus.EXPIRED, TaskStatus.TAKEOVER, TaskStatus.RESULT_UNKNOWN, TaskStatus.PENDING_PAYMENT, TaskStatus.SUBMIT_REJECTED) &&
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

    fun recordAccessibilityEvent(
        pageState: String?,
        action: String? = null,
        automationStage: String? = null,
        rootPackage: String? = null,
        evidenceSource: String? = null,
        contextStatus: String? = null,
        missingEvidence: String? = null,
        contextAt: Long? = null,
        snapshotFingerprint: String? = null,
        saleT0At: Long? = null
    ) {
        val task = load() ?: return
        val now = System.currentTimeMillis()
        save(task.copy(
            lastPageState = pageState ?: task.lastPageState,
            lastAction = action ?: task.lastAction,
            lastAutomationStage = automationStage ?: task.lastAutomationStage,
            lastRootPackage = rootPackage ?: task.lastRootPackage,
            lastEvidenceSource = evidenceSource ?: task.lastEvidenceSource,
            lastSearchContextStatus = contextStatus ?: task.lastSearchContextStatus,
            lastMissingEvidence = missingEvidence ?: task.lastMissingEvidence,
            lastSearchContextAt = contextAt ?: task.lastSearchContextAt,
            lastSearchSnapshotFingerprint = snapshotFingerprint ?: task.lastSearchSnapshotFingerprint,
            lastSaleT0At = saleT0At ?: task.lastSaleT0At,
            lastAccessibilityEventAt = now,
            accessibilityEventCount = task.accessibilityEventCount + 1
        ))
    }

    fun recordSaleT0(event: String) {
        val task = load() ?: return
        val now = System.currentTimeMillis()
        save(task.copy(
            status = TaskStatus.SALE_T0,
            enabled = true,
            lastEvent = event,
            lastEventAt = now,
            lastSaleT0At = now
        ))
    }

    /** Persists the cold-start attempt without storing account or page content. */
    fun recordColdStartAttempt(event: String, failure: String? = null) {
        val task = load() ?: return
        val now = System.currentTimeMillis()
        save(task.copy(
            status = TaskStatus.OPENING_OFFICIAL_APP,
            enabled = true,
            lastEvent = event,
            lastEventAt = now,
            lastError = failure,
            coldStartAt = task.coldStartAt ?: now,
            coldStartAttempts = task.coldStartAttempts + 1,
            coldStartLastFailure = failure
        ))
    }

    fun recordColdStartState(status: TaskStatus, event: String, failure: String? = null) {
        require(status in COLD_START_STATUSES) { "不是冷启动状态：$status" }
        val task = load() ?: return
        val now = System.currentTimeMillis()
        save(task.copy(
            status = status,
            enabled = true,
            lastEvent = event,
            lastEventAt = now,
            lastError = failure,
            coldStartAt = task.coldStartAt ?: now,
            coldStartLastFailure = failure
        ))
    }

    fun recordWindowChange(rootPackage: String?, event: String) {
        val task = load() ?: return
        val now = System.currentTimeMillis()
        save(task.copy(
            lastRootPackage = rootPackage ?: task.lastRootPackage,
            lastWindowChangedAt = now,
            lastEvent = event,
            lastEventAt = now
        ))
    }

    /** Re-enables only a pre-submit takeover for an already-on-sale task. */
    fun prepareManualRetry(): TicketTask? {
        val task = load() ?: return null
        if (task.status != TaskStatus.TAKEOVER || task.saleState != SaleState.ALREADY_ON_SALE) return task
        save(task.copy(
            enabled = true,
            status = TaskStatus.PREPARING,
            lastEvent = "用户请求重新尝试官方 App 冷启动",
            lastEventAt = System.currentTimeMillis(),
            lastError = null,
            coldStartLastFailure = null
        ))
        return load()
    }

    fun clearSearchSnapshot() {
        val task = load() ?: return
        save(task.copy(
            lastSearchContextStatus = null,
            lastMissingEvidence = null,
            lastSearchContextAt = null,
            lastSearchSnapshotFingerprint = null
        ))
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "active_task"
        val COLD_START_STATUSES = setOf(
            TaskStatus.COLD_START,
            TaskStatus.OPENING_OFFICIAL_APP,
            TaskStatus.OPENING_HOME,
            TaskStatus.FILLING_SEARCH_FORM,
            TaskStatus.WAITING_SEARCH_RESULT
        )
    }
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
