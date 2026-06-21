package com.gst.latestpack

import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min

class Downloader(
    private val concurrency: Int = 4,
    private val timeoutSec: Int = 30,
    private val retries: Int = 3
) {

    private val threadPool = Dispatchers.IO.limitedParallelism(concurrency)

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val speedBytesPerSec: Long
    )

    suspend fun downloadFile(
        url: String,
        targetFile: File,
        onProgress: (Progress) -> Unit = {}
    ) {
        withContext(threadPool) {
            val fileSize = getFileSize(url)
            if (fileSize > CHUNK_THRESHOLD) {
                downloadChunked(url, targetFile, fileSize, onProgress)
            } else {
                downloadSingle(url, targetFile, onProgress)
            }
        }
    }

    private fun getFileSize(url: String): Long {
        val timeoutMs = timeoutSec * 1000
        var lastException: Exception? = null
        repeat(retries) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", UA)
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                try {
                    conn.connect()
                    return conn.contentLengthLong
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: RuntimeException("Failed to get file size: $url")
    }

    private suspend fun downloadChunked(
        url: String,
        targetFile: File,
        fileSize: Long,
        onProgress: (Progress) -> Unit
    ) {
        val chunkSize = CHUNK_SIZE
        val chunks = mutableListOf<LongRange>()
        var start = 0L
        while (start < fileSize) {
            val end = min(start + chunkSize - 1, fileSize - 1)
            chunks.add(start..end)
            start = end + 1
        }

        val tempDir = Files.createTempDirectory("latestpack_download").toFile()
        var downloadedTotal = 0L
        val startTime = System.currentTimeMillis()

        try {
            coroutineScope {
                val deferreds = chunks.mapIndexed { index, range ->
                    async(threadPool) {
                        val chunkFile = File(tempDir, "chunk_$index")
                        downloadRange(url, range, chunkFile)
                        synchronized(this@Downloader) {
                            downloadedTotal += (range.last - range.first + 1)
                            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                            val speed = downloadedTotal * 1000 / elapsed
                            onProgress(Progress(downloadedTotal, fileSize, speed))
                        }
                    }
                }
                deferreds.awaitAll()
            }

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { fos ->
                chunks.indices.forEach { index ->
                    File(tempDir, "chunk_$index").inputStream().use { it.copyTo(fos) }
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun downloadRange(url: String, range: LongRange, targetFile: File) {
        val timeoutMs = timeoutSec * 1000
        var lastException: Exception? = null
        repeat(retries) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
                conn.setRequestProperty("User-Agent", UA)
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.connect()
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    throw RuntimeException("Chunk download failed: HTTP $code for range ${range.first}-${range.last}")
                }
                conn.inputStream.use { input ->
                    Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                conn.disconnect()
                return
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: RuntimeException("Chunk download failed after $retries retries")
    }

    private fun downloadSingle(
        url: String,
        targetFile: File,
        onProgress: (Progress) -> Unit
    ) {
        val timeoutMs = timeoutSec * 1000
        var lastException: Exception? = null
        repeat(retries) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", UA)
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.connect()
                val total = conn.contentLengthLong
                targetFile.parentFile?.mkdirs()
                val startTime = System.currentTimeMillis()
                var downloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                            val speed = downloaded * 1000 / elapsed
                            onProgress(Progress(downloaded, total, speed))
                        }
                    }
                }
                conn.disconnect()
                return
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: RuntimeException("Download failed after $retries retries: $url")
    }

    suspend fun downloadParallel(
        tasks: List<DownloadTask>,
        onProgress: (taskIndex: Int, Progress) -> Unit = { _, _ -> }
    ) {
        coroutineScope {
            tasks.mapIndexed { index, task ->
                async(threadPool) {
                    downloadFile(task.url, task.targetFile) { progress ->
                        onProgress(index, progress)
                    }
                }
            }.awaitAll()
        }
    }

    data class DownloadTask(
        val url: String,
        val targetFile: File
    )

    companion object {
        private const val UA = "LeastPack_Client/v1.0.0"
        private const val BUFFER_SIZE = 8192
        private const val CHUNK_THRESHOLD = 32L * 1024 * 1024 // 32MB
        private const val CHUNK_SIZE = 8L * 1024 * 1024 // 8MB per chunk
    }
}
