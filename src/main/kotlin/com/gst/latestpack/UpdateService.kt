package com.gst.latestpack

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class ChangeAction { ADD, MODIFY, DELETE }

data class FileChange(
    val action: ChangeAction,
    val path: String
)

data class VersionChange(
    val version: String,
    val timestamp: Long,
    val changes: List<FileChange>
)

data class LatestVersion(
    val version: String,
    val timestamp: Long
)

data class MergedChange(
    val action: ChangeAction,
    val path: String,
    val version: String
)

class UpdateService(private val config: AppConfig) {

    private val mapper = ObjectMapper()
    private val downloader = Downloader(
        concurrency = 4,
        timeoutSec = config.httpTimeout,
        retries = config.retries
    )
    private val baseUrl = config.serverUrl.trimEnd('/')

    suspend fun checkForUpdate(): LatestVersion? {
        val localVersion = LocalVersion.read(config.targetDir)
        val latest = fetchLatestVersion() ?: return null
        return if (localVersion != latest.version) latest else null
    }

    suspend fun runUpdate(dialog: DownloadDialog) {
        try {
            val localVersion = LocalVersion.read(config.targetDir)
            val latest = fetchLatestVersion()
                ?: throw RuntimeException("无法获取最新版本信息")

            if (localVersion == latest.version) {
                dialog.setStatus("已是最新版本")
                return
            }

            dialog.setVersion("${localVersion ?: "无"} → ${latest.version}")
            dialog.setStatus("正在获取更新列表...")

            val versions = fetchUpdates(localVersion ?: "")
            if (versions.isEmpty()) {
                dialog.setStatus("无需更新")
                return
            }

            val merged = mergeChanges(versions)
            val toDownload = merged.filter { it.action != ChangeAction.DELETE }
            val toDelete = merged.filter { it.action == ChangeAction.DELETE }

            if (toDownload.isEmpty() && toDelete.isEmpty()) {
                dialog.setStatus("无需更新")
                return
            }

            // Execute deletions first
            toDelete.forEach { change ->
                val file = File(config.targetDir, change.path)
                if (file.exists()) file.delete()
            }

            // Download files in parallel
            if (toDownload.isNotEmpty()) {
                dialog.setStatus("正在下载文件 (0/${toDownload.size})...")
                downloadAll(toDownload, dialog)
            }

            // Update local version
            LocalVersion.write(config.targetDir, latest.version)
            dialog.setStatus("更新完成")
            dialog.setProgress(1, 1, 0)
        } catch (e: Exception) {
            dialog.setStatus("更新失败: ${e.message}")
            throw e
        }
    }

    private fun fetchLatestVersion(): LatestVersion? {
        val conn = httpGet("$baseUrl/api/client/latest") ?: return null
        try {
            val tree = mapper.readTree(conn.inputStream)
            return LatestVersion(
                version = tree["version"].asText(),
                timestamp = tree["timestamp"].asLong()
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchUpdates(fromVersion: String): List<VersionChange> {
        val url = if (fromVersion.isEmpty()) {
            "$baseUrl/api/client/updates"
        } else {
            "$baseUrl/api/client/updates?from=${java.net.URLEncoder.encode(fromVersion, "UTF-8")}"
        }
        val conn = httpGet(url) ?: return emptyList()
        try {
            val tree = mapper.readTree(conn.inputStream)
            val versionsNode = tree["versions"] ?: return emptyList()
            return versionsNode.map { vNode ->
                VersionChange(
                    version = vNode["version"].asText(),
                    timestamp = vNode["timestamp"].asLong(),
                    changes = (vNode["changes"] ?: emptyList()).map { cNode ->
                        FileChange(
                            action = ChangeAction.valueOf(cNode["action"].asText().uppercase()),
                            path = cNode["path"].asText()
                        )
                    }
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    fun mergeChanges(versions: List<VersionChange>): List<MergedChange> {
        // Map from path to the latest change for that path
        val pathMap = java.util.LinkedHashMap<String, MergedChange>()

        // Process versions in chronological order (earliest first)
        for (version in versions.sortedBy { it.timestamp }) {
            for (change in version.changes) {
                pathMap[change.path] = MergedChange(
                    action = change.action,
                    path = change.path,
                    version = version.version
                )
            }
        }

        // Remove entries where the final action is DELETE (no subsequent ADD)
        val iterator = pathMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.action == ChangeAction.DELETE) {
                iterator.remove()
            }
        }

        return pathMap.values.toList()
    }

    private suspend fun downloadAll(
        changes: List<MergedChange>,
        dialog: DownloadDialog
    ) {
        val tasks = changes.map { change ->
            val url = buildDownloadUrl(change.version, change.path)
            val targetFile = File(config.targetDir, change.path)
            Downloader.DownloadTask(url, targetFile)
        }

        var completedCount = 0
        val totalFiles = tasks.size

        downloader.downloadParallel(tasks) { taskIndex, progress ->
            val change = changes[taskIndex]
            SwingUtilities.invokeLater {
                dialog.setFileName(change.path)
                dialog.setProgress(progress.downloaded, progress.total, progress.speedBytesPerSec)
            }

            // Check if this task is done (progress equals total or total is unknown but we got here)
            if (progress.total > 0 && progress.downloaded >= progress.total) {
                synchronized(this) {
                    completedCount++
                    dialog.setStatus("正在下载文件 ($completedCount/$totalFiles)...")
                }
            }
        }

        dialog.setStatus("正在下载文件 ($totalFiles/$totalFiles)...")
    }

    private fun buildDownloadUrl(version: String, path: String): String {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val encodedVersion = java.net.URLEncoder.encode(version, "UTF-8")
        return "$baseUrl/api/client/update/download/$encodedVersion?path=$encodedPath"
    }

    private fun httpGet(url: String): HttpURLConnection? {
        val timeoutMs = config.httpTimeout * 1000
        var lastException: Exception? = null
        repeat(config.retries) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", UA)
                conn.connect()
                if (conn.responseCode == HttpURLConnection.HTTP_OK) return conn
                conn.disconnect()
            } catch (e: Exception) {
                lastException = e
            }
        }
        return null
    }
}

private typealias SwingUtilities = javax.swing.SwingUtilities

private const val UA = "LeastPack_Client/v1.0.0"
