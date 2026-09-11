package com.example.ticketassistant.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.ticketassistant.data.TicketTask
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds a report that is useful for debugging without exporting account or payment data. */
fun buildDiagnosticReport(task: TicketTask, generatedAt: Instant = Instant.now()): String {
    val fields = linkedMapOf<String, String?>()
    fields["formatVersion"] = "3"
    fields["generatedAt"] = generatedAt.toString()
    fields["automationMode"] = "任务信息自动查询（诊断）"
    fields["taskId"] = task.taskId
    fields["travelDate"] = task.date
    fields["from"] = task.from.name
    fields["to"] = task.to.name
    fields["train"] = task.train.trainNo
    fields["seat"] = task.seat
    fields["passenger"] = if (task.passengerName.isBlank()) "未配置" else "已配置（已脱敏）"
    fields["saleState"] = task.saleState.name
    fields["saleDateTime"] = task.saleDateTime
    fields["status"] = task.status.name
    fields["lastEvent"] = task.lastEvent?.let { "已记录（内容已脱敏）" }
    fields["lastEventAt"] = task.lastEventAt?.toString()
    fields["lastError"] = task.lastError?.let { "已记录（内容已脱敏）" }
    fields["lastPageState"] = task.lastPageState
    fields["lastAction"] = task.lastAction?.let { "已记录（内容已脱敏）" }
    fields["lastAutomationStage"] = task.lastAutomationStage
    fields["lastRootPackage"] = task.lastRootPackage
    fields["lastEvidenceSource"] = task.lastEvidenceSource
    fields["lastAccessibilityEventAt"] = task.lastAccessibilityEventAt?.toString()
    fields["accessibilityEventCount"] = task.accessibilityEventCount.toString()
    fields["containsCredentials"] = "false"
    fields["containsPaymentData"] = "false"
    return fields.entries.joinToString(",\n", prefix = "{\n", postfix = "\n}") { (key, value) ->
        "  ${jsonString(key)}: ${value?.let(::jsonString) ?: "null"}"
    }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

object DiagnosticExporter {
    fun export(context: Context, task: TicketTask, generatedAt: Instant = Instant.now()): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("无法访问应用专用诊断目录")
        if (!directory.exists() && !directory.mkdirs()) error("无法创建诊断目录")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(generatedAt)
        val file = File(directory, "ticket-assistant-diagnostic-$stamp.json")
        file.writeText(buildDiagnosticReport(task, generatedAt), Charsets.UTF_8)
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
