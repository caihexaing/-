package com.example.ticketassistant.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdateDownloadStage {
    CONNECTING,
    DOWNLOADING,
    VERIFYING
}

data class AppUpdate(
    val versionName: String,
    val releaseName: String,
    val notes: String,
    val downloadUrl: String,
    val expectedSize: Long? = null,
    val sha256: String? = null
)

data class UpdateProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val attempt: Int,
    val stage: UpdateDownloadStage = UpdateDownloadStage.CONNECTING
)

open class UpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

private class UpdateValidationException(message: String) : UpdateDownloadException(message)

internal enum class DownloadResumeDecision {
    START,
    RESUME,
    RESTART,
    COMPLETE
}

internal fun downloadResumeDecision(
    existingBytes: Long,
    responseCode: Int,
    expectedSize: Long?
): DownloadResumeDecision = when {
    existingBytes <= 0L -> DownloadResumeDecision.START
    responseCode == HttpURLConnection.HTTP_PARTIAL -> DownloadResumeDecision.RESUME
    responseCode == HTTP_RANGE_NOT_SATISFIABLE &&
        expectedSize != null && existingBytes == expectedSize -> DownloadResumeDecision.COMPLETE
    else -> DownloadResumeDecision.RESTART
}

internal data class DownloadContentRange(
    val start: Long,
    val end: Long,
    val total: Long?
)

internal fun parseContentRange(value: String?): DownloadContentRange? {
    val match = DOWNLOAD_CONTENT_RANGE_PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (total != null && total <= end) return null
    return DownloadContentRange(start, end, total)
}

private val DOWNLOAD_CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

class UpdateChecker {
    suspend fun latest(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TicketAssistant/$currentVersion")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (connection.responseCode !in 200..299) return@withContext null
            val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = root.optString("tag_name").removePrefix("v").trim()
            val assets = root.optJSONArray("assets")
            val asset = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val downloadUrl = asset?.optString("browser_download_url").orEmpty()
            if (tag.isBlank() || downloadUrl.isBlank() || !isNewer(tag, currentVersion)) return@withContext null

            AppUpdate(
                versionName = tag,
                releaseName = root.optString("name").ifBlank { "行程助手 $tag" },
                notes = root.optString("body"),
                downloadUrl = downloadUrl,
                expectedSize = asset?.optLong("size")?.takeIf { it > 0 },
                sha256 = asset?.optString("digest").orEmpty()
                    .removePrefix("sha256:").lowercase().ifBlank { null }
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdate,
        onProgress: (UpdateProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        ensureInstallPermission(context)
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw UpdateDownloadException("无法访问应用专用下载目录")
        val target = File(directory, "ticket-assistant-${update.versionName}.apk")
        val staging = File(directory, "ticket-assistant-${update.versionName}.part")

        if (target.isFile) {
            onProgress(UpdateProgress(target.length(), update.expectedSize ?: target.length(), 1, UpdateDownloadStage.VERIFYING))
            try {
                validate(context, target, update)
                launchInstaller(context, target)
                return@withContext
            } catch (_: UpdateValidationException) {
                target.delete()
            }
        }

        if (staging.isFile && update.expectedSize != null && staging.length() == update.expectedSize) {
            onProgress(UpdateProgress(staging.length(), update.expectedSize, 1, UpdateDownloadStage.VERIFYING))
            try {
                validate(context, staging, update)
                if (!staging.renameTo(target)) throw UpdateDownloadException("无法保存已下载的更新文件")
                launchInstaller(context, target)
                return@withContext
            } catch (_: UpdateValidationException) {
                staging.delete()
            }
        }

        var lastFailure: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadWithHttp(update, attempt, staging, onProgress)
                onProgress(
                    UpdateProgress(
                        staging.length(),
                        update.expectedSize ?: staging.length(),
                        attempt + 1,
                        UpdateDownloadStage.VERIFYING
                    )
                )
                validate(context, staging, update)
                target.delete()
                if (!staging.renameTo(target)) throw UpdateDownloadException("无法保存已下载的更新文件")
                launchInstaller(context, target)
                return@withContext
            } catch (error: Throwable) {
                lastFailure = error
                if (error is UpdateValidationException) staging.delete()
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) delay(RETRY_DELAYS_MS[attempt])
            }
        }
        throw UpdateDownloadException(lastFailure?.message ?: "下载更新失败，请检查网络后重试", lastFailure)
    }

    private suspend fun ensureInstallPermission(context: Context) {
        if (context.packageManager.canRequestPackageInstalls()) return
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        throw UpdateDownloadException("请先允许本应用安装未知来源应用，然后再次点击立即更新")
    }

    private suspend fun downloadWithHttp(
        update: AppUpdate,
        attempt: Int,
        destination: File,
        onProgress: (UpdateProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val existingBytes = destination.length().coerceAtLeast(0L)
        val reporter = ProgressReporter(onProgress)
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", APK_MIME_TYPE)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "TicketAssistant/${update.versionName}")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
        try {
            reporter.report(
                UpdateProgress(
                    existingBytes,
                    update.expectedSize ?: -1L,
                    attempt + 1,
                    UpdateDownloadStage.CONNECTING
                ),
                force = true
            )
            val responseCode = connection.responseCode
            val resumeDecision = downloadResumeDecision(existingBytes, responseCode, update.expectedSize)
            if (resumeDecision == DownloadResumeDecision.COMPLETE) return@withContext
            if (responseCode !in 200..299) {
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    destination.delete()
                    throw UpdateDownloadException("断点续传范围已失效，准备重新下载")
                }
                throw UpdateDownloadException("GitHub 下载失败（HTTP $responseCode）")
            }
            if (resumeDecision == DownloadResumeDecision.RESTART) destination.outputStream().use { }
            val append = resumeDecision == DownloadResumeDecision.RESUME
            val contentRange = if (append) {
                parseContentRange(connection.getHeaderField("Content-Range"))
                    ?: run {
                        destination.delete()
                        throw UpdateDownloadException("服务器未返回有效的续传范围，准备重新下载")
                    }
            } else {
                null
            }
            if (contentRange != null && contentRange.start != existingBytes) {
                destination.delete()
                throw UpdateDownloadException("服务器续传起点不匹配，准备重新下载")
            }
            val startOffset = if (append) existingBytes else 0L
            val totalBytes = if (append) {
                update.expectedSize
                    ?: contentRange?.total
                    ?: connection.contentLengthLong.takeIf { it >= 0L }?.let { it + startOffset }
                    ?: -1L
            } else {
                update.expectedSize ?: connection.contentLengthLong
            }
            var downloadedBytes = startOffset
            reporter.report(
                UpdateProgress(downloadedBytes, totalBytes, attempt + 1, UpdateDownloadStage.DOWNLOADING),
                force = true
            )
            FileOutputStream(destination, append).buffered(BUFFER_SIZE).use { output ->
                connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        reporter.report(
                            UpdateProgress(downloadedBytes, totalBytes, attempt + 1, UpdateDownloadStage.DOWNLOADING),
                            force = totalBytes > 0L && downloadedBytes == totalBytes
                        )
                    }
                }
            }
            reporter.report(
                UpdateProgress(downloadedBytes, totalBytes, attempt + 1, UpdateDownloadStage.DOWNLOADING),
                force = true
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun validate(context: Context, file: File, update: AppUpdate) {
        update.expectedSize?.let { expected ->
            if (file.length() != expected) {
                throw UpdateValidationException("下载文件不完整（${file.length()} / $expected 字节）")
            }
        }
        update.sha256?.let { expectedHash ->
            val actualHash = sha256(file)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw UpdateValidationException("更新文件校验失败（SHA-256 不匹配）")
            }
        }
        validatePackage(context, file)
    }

    private fun validatePackage(context: Context, file: File) {
        val packageManager = context.packageManager
        val archive = archiveInfo(packageManager, file)
            ?: throw UpdateValidationException("下载文件不是可安装的 APK")
        if (archive.packageName != context.packageName) {
            throw UpdateValidationException("更新包的应用标识不匹配")
        }
        val installed = packageManager.getPackageInfo(context.packageName, signingFlags())
        if (versionCode(archive) <= versionCode(installed)) {
            throw UpdateValidationException("更新包版本未高于当前版本")
        }
        if (!sameSigner(archive, installed)) {
            throw UpdateValidationException("更新包签名与当前应用不一致")
        }
    }

    private class ProgressReporter(private val callback: (UpdateProgress) -> Unit) {
        private var lastReportedAt = 0L
        private var lastStage: UpdateDownloadStage? = null

        fun report(progress: UpdateProgress, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && progress.stage == lastStage && now - lastReportedAt < PROGRESS_CALLBACK_INTERVAL_MS) return
            lastReportedAt = now
            lastStage = progress.stage
            callback(progress)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(packageManager: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            packageManager.getPackageArchiveInfo(file.path, signingFlags())
        }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun sameSigner(candidate: PackageInfo, installed: PackageInfo): Boolean {
        val candidateSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidate.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            candidate.signatures.orEmpty()
        }
        val installedSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            installed.signatures.orEmpty()
        }
        return candidateSigners.map { sha256(it.toByteArray()) }.toSet() ==
            installedSigners.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val candidateParts = parts(candidate)
        val currentParts = parts(current)
        for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val left = candidateParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/caihexaing/-/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_CALLBACK_INTERVAL_MS = 120L
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
    }
}
