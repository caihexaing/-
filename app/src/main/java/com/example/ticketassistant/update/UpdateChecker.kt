package com.example.ticketassistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionName: String,
    val releaseName: String,
    val notes: String,
    val downloadUrl: String
)

class UpdateChecker {
    suspend fun latest(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TicketAssistant/$currentVersion")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (connection.responseCode !in 200..299) return@withContext null
            val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = root.optString("tag_name").removePrefix("v").trim()
            val asset = (0 until root.optJSONArray("assets").orEmptyLength())
                .map { root.optJSONArray("assets")!!.optJSONObject(it) }
                .firstOrNull { it?.optString("name").orEmpty().endsWith(".apk", ignoreCase = true) }
            val url = asset?.optString("browser_download_url").orEmpty()
            if (tag.isBlank() || url.isBlank() || !isNewer(tag, currentVersion)) return@withContext null
            AppUpdate(tag, root.optString("name").ifBlank { "行程助手 $tag" }, root.optString("body"), url)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(context: Context, update: AppUpdate) = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "ticket-assistant-${update.versionName}.apk")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "TicketAssistant/${update.versionName}")
        }
        try {
            if (connection.responseCode !in 200..299) error("下载更新失败：HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        withContext(Dispatchers.Main) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                error("请先允许本应用安装未知来源应用，然后再次点击立即更新")
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }; val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun org.json.JSONArray?.orEmptyLength() = this?.length() ?: 0

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/caihexaing/-/releases/latest"
    }
}
