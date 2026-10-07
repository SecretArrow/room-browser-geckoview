package com.roombrowser.browser.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.DownloadStatus
import com.roombrowser.domain.download.DownloadFormat
import com.roombrowser.domain.download.DownloadPlanner
import com.roombrowser.domain.download.ResumePlan
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile-scoped download engine.
 *
 * Features: queue, pause, resume (HTTP Range), cancel, retry, open, share,
 * delete, duplicate filename handling, notifications, correct scoped
 * storage usage (MediaStore Downloads on API 29+, legacy public dir on 28).
 *
 * Resuming is the part that needs care, so it is not decided here: the "where
 * do I append" question is answered by [DownloadPlanner] in the pure domain
 * module, where a JVM test can hold it to the rule that the offset we write at
 * must be the offset the server actually sent from.
 */
class DownloadEngine(
    private val context: Context,
    private val repo: BrowserRepository,
    private val client: OkHttpClient,
    private val profileId: ProfileId
) {

    companion object {
        const val CHANNEL_PROGRESS = "downloads_progress"
        const val CHANNEL_DONE = "downloads_done"
        const val MAX_PARALLEL = 2
        private const val NOTIF_TAG = "rb_dl"
        private const val PROGRESS_BYTES = 64 * 1024
        private const val PROGRESS_MS = 1_000L

        /**
         * Cap on app-produced bytes published through [saveStream]. A rendered
         * page can be enormous; refusing beats filling the user's storage.
         */
        const val MAX_INLINE_BYTES = 64L * 1024 * 1024

        /** The action the notification buttons broadcast. */
        const val ACTION = "com.roombrowser.DOWNLOAD_ACTION"

        /**
         * The engine the notification buttons act on.
         *
         * A BroadcastReceiver is instantiated by the system and has no route to
         * the ViewModel that owns the engine, so the live engine publishes
         * itself here on creation and withdraws on shutdown. Exactly one engine
         * exists at a time (the ViewModel builds a new one per profile switch),
         * which is what makes a single slot correct rather than a shortcut.
         */
        @Volatile
        var current: DownloadEngine? = null
            private set

        /**
         * Clears a progress notification that nothing is behind any more.
         *
         * The progress notification is `setOngoing(true)`, which makes it
         * non-dismissible by design — the user should not be able to swipe
         * away a running transfer. When the engine process is killed mid
         * transfer that same flag turns it into litter the user has no way to
         * remove, with Pause and Cancel buttons that cannot do anything.
         * [DownloadActionReceiver] calls this when it finds no live engine.
         */
        fun dismissOrphanedNotification(context: Context, id: Long) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            runCatching { nm.cancel(NOTIF_TAG, id.toInt()) }
        }

        /** RFC 6266-ish filename extraction used by WebView download events. */
        fun guessFileName(url: String, contentDisposition: String?, mimeType: String): String {
            val fromDisposition = contentDisposition?.let { disposition ->
                Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(disposition)?.groupValues?.get(1)
            }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (!fromDisposition.isNullOrBlank()) return sanitize(fromDisposition)
            val fromUrl = url.substringBefore('?').substringAfterLast('/').let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            if (fromUrl.isNotBlank() && fromUrl.contains('.')) return sanitize(fromUrl)
            val ext = when (mimeType.substringBefore(';').lowercase()) {
                "text/html" -> ".html"
                "text/plain" -> ".txt"
                "image/png" -> ".png"
                "image/jpeg" -> ".jpg"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                "application/pdf" -> ".pdf"
                "application/zip" -> ".zip"
                "audio/mpeg" -> ".mp3"
                "video/mp4" -> ".mp4"
                else -> ""
            }
            return "download-${System.currentTimeMillis()}$ext"
        }

        private fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\.{2,}"), ".")
                .take(120).ifBlank { "download" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Concurrent maps, not `mutableMapOf`/`mutableSetOf`: these are touched from
     * the UI thread (pause/cancel) and from IO dispatcher jobs at the same time,
     * and a plain HashMap mutated from two threads can corrupt its own buckets.
     */
    private val running = ConcurrentHashMap<Long, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    private val paused = ConcurrentHashMap.newKeySet<Long>()

    /** id -> bytes per second, sampled once a second; live only, never stored. */
    private val _speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val speeds: StateFlow<Map<Long, Long>> = _speeds

    init {
        current = this
    }

    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun enqueue(url: String, suggestedName: String, mime: String, userAgent: String?) {
        scope.launch {
            val entity = DownloadEntity(
                profileId = profileId.value,
                url = url,
                fileName = dedupeName(profileId, suggestedName),
                mimeType = mime.ifBlank { "application/octet-stream" },
                destination = "",
                totalBytes = -1,
                downloadedBytes = 0,
                status = DownloadStatus.QUEUED.name,
                createdAt = System.currentTimeMillis(),
                userAgent = userAgent.orEmpty()
            )
            repo.insertDownload(entity)
            pump()
        }
    }

    /**
     * Stop the transfer and keep the bytes.
     *
     * The id is recorded in [paused] before the job is cancelled because the
     * job's own failure path has to be able to tell "the user paused this" from
     * "this broke" — and by then the Job is already out of [running], so asking
     * it is not an option.
     */
    fun pause(id: Long) {
        paused += id
        running.remove(id)?.cancel()
        scope.launch {
            repo.download(id)?.let {
                if (it.status == DownloadStatus.RUNNING.name || it.status == DownloadStatus.QUEUED.name) {
                    repo.updateDownloadStatus(id, DownloadStatus.PAUSED.name, null)
                    notifyPaused(id, it.fileName, it.downloadedBytes, it.totalBytes)
                }
            }
        }
    }

    /**
     * Queue a stopped download again. Accepts FAILED and CANCELLED as well as
     * PAUSED: the row offers Retry for a cancelled download, and a "Retry" that
     * silently did nothing was one of the ways this screen lied.
     */
    fun resume(id: Long) {
        paused -= id
        cancelled -= id
        scope.launch {
            repo.download(id)?.let {
                val status = it.status
                if (status == DownloadStatus.PAUSED.name ||
                    status == DownloadStatus.FAILED.name ||
                    status == DownloadStatus.CANCELLED.name
                ) {
                    repo.updateDownload(it.copy(status = DownloadStatus.QUEUED.name, error = null))
                    pump()
                }
            }
        }
    }

    fun cancel(id: Long) {
        cancelled += id
        paused -= id
        running.remove(id)?.cancel()
        scope.launch {
            repo.download(id)?.let {
                repo.updateDownloadStatus(id, DownloadStatus.CANCELLED.name, null)
            }
        }
        dismiss(id)
    }

    fun retry(id: Long) = resume(id)

    fun delete(id: Long) {
        cancel(id)
        scope.launch {
            val dl = repo.download(id)
            partFileFor(id).delete()
            // The published copy is the user's file now — deleting the row
            // without it would leave an orphan in Downloads that nothing lists.
            runCatching { dl?.destination?.takeIf { it.isNotBlank() }?.let { deletePublished(it) } }
            repo.deleteDownload(id)
            paused -= id
            cancelled -= id
            _speeds.value = _speeds.value - id
            dismiss(id)
        }
    }

    /** Kick the queue after any state change. */
    fun pump() {
        scope.launch {
            val active = repo.activeDownloads(profileId)
            val runningCount = active.count { it.status == DownloadStatus.RUNNING.name }
            if (runningCount >= MAX_PARALLEL) return@launch
            active.filter { it.status == DownloadStatus.QUEUED.name }
                .take(MAX_PARALLEL - runningCount)
                .forEach { start(it) }
        }
    }

    /**
     * Pick up where a previous engine left off.
     *
     * A new engine has no jobs, so any row still marked RUNNING belongs to a
     * process that is gone — a profile switch, or the app being killed. Left
     * alone those rows sit at RUNNING forever, which is worse here than a stale
     * label: the pump counts them against [MAX_PARALLEL], so two orphans would
     * stop every later download from ever starting.
     *
     * They are re-queued rather than parked, because "still downloading" is
     * what the user was told and the part file on disk is real — the resume
     * path reads its length, so continuing is safe even if the cache was
     * cleared underneath it.
     */
    fun recover() {
        scope.launch {
            repo.activeDownloads(profileId)
                .filter { it.status == DownloadStatus.RUNNING.name }
                .forEach { repo.updateDownloadStatus(it.id, DownloadStatus.QUEUED.name, null) }
            pump()
        }
    }

    private fun partFileFor(id: Long): File = File(context.cacheDir, "downloads/$id.part")

    private fun start(entity: DownloadEntity) {
        val job = scope.launch {
            repo.updateDownloadStatus(entity.id, DownloadStatus.RUNNING.name, null)
            try {
                val partFile = partFileFor(entity.id).apply { parentFile?.mkdirs() }
                val declaredTotal = transfer(entity, partFile)
                val size = partFile.length()
                val destination = publish(entity, partFile)
                repo.completeDownload(
                    id = entity.id,
                    status = DownloadStatus.COMPLETED.name,
                    destination = destination,
                    downloaded = size,
                    total = if (declaredTotal > 0) declaredTotal else size,
                    completedAt = System.currentTimeMillis()
                )
                _speeds.value = _speeds.value - entity.id
                paused -= entity.id
                cancelled -= entity.id
                notifyDone(entity.id, entity.fileName, destination)
            } catch (e: Throwable) {
                // NonCancellable: a paused or cancelled job is already cancelled,
                // and a plain suspend write here would throw before it recorded
                // anything — which is how a paused download came to be filed as
                // FAILED, or to keep a RUNNING row forever.
                withContext(NonCancellable) { recordFailure(entity, e) }
            }
            pump()
        }
        running[entity.id] = job
        // remove(key, value) so a finishing old job can never evict a newer one
        // that a quick retry has already put in the same slot.
        job.invokeOnCompletion { running.remove(entity.id, job) }
    }

    /**
     * Streams the body into the part file and returns the finished size the
     * server declared, or -1 when it never said.
     */
    private suspend fun transfer(entity: DownloadEntity, partFile: File): Long {
        // The file — not the stored counter — is the truth about what we hold.
        // The counter is written on a throttle, so trusting it can ask the
        // server to resume from a byte we never actually wrote down.
        val held = if (partFile.isFile) partFile.length() else 0L

        val (response, plan) = openResponse(entity, held)
        response.use { resp ->
            val body = resp.body ?: throw IOException("Empty body")
            // append = plan.appendAt > 0. Opening without append truncates, and
            // the old code opened the file that way *before* deciding whether it
            // meant to resume — so every resumed download silently lost its
            // first N bytes and still reported success.
            val output = FileOutputStream(partFile, plan.appendAt > 0)
            output.use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(16 * 1024)
                var lastBytes = 0L
                var lastAt = SystemClock.elapsedRealtime()
                var sampledAt = lastAt
                var sampledBytes = held
                while (true) {
                    // Checked every 16 KiB so a pause lands promptly rather than
                    // at the next throttled progress write.
                    if (entity.id in cancelled || entity.id in paused) throw CancelledException()
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    val downloaded = partFile.length()
                    val now = SystemClock.elapsedRealtime()
                    if (downloaded - lastBytes > PROGRESS_BYTES || now - lastAt > PROGRESS_MS) {
                        lastBytes = downloaded
                        lastAt = now
                        val speed = sampleSpeed(entity.id, downloaded, now, sampledAt, sampledBytes)
                        if (speed != null) {
                            sampledAt = now
                            sampledBytes = downloaded
                        }
                        repo.updateDownloadProgress(entity.id, downloaded, plan.totalBytes)
                        notifyProgress(entity.id, entity.fileName, downloaded, plan.totalBytes, speed)
                    }
                }
            }
        }
        return plan.totalBytes
    }

    /**
     * Opens the response for [held] bytes already on disk, re-requesting once
     * from the top if the server's answer cannot be appended to.
     */
    private fun openResponse(entity: DownloadEntity, held: Long): Pair<Response, ResumePlan> {
        val first = execute(entity, held)
        val plan = DownloadPlanner.plan(
            resumeFrom = held,
            responseCode = first.code,
            contentRange = first.header("Content-Range"),
            contentLength = first.body?.contentLength() ?: -1L
        )
        if (!plan.refetch) return first to plan
        first.close()
        val second = execute(entity, 0L)
        val secondPlan = DownloadPlanner.plan(
            resumeFrom = 0L,
            responseCode = second.code,
            contentRange = second.header("Content-Range"),
            contentLength = second.body?.contentLength() ?: -1L
        )
        return second to secondPlan
    }

    private fun execute(entity: DownloadEntity, from: Long): Response {
        val request = Request.Builder()
            .url(entity.url)
            .apply {
                if (from > 0) header("Range", "bytes=$from-")
                // The profile presents a device; a download that fetched with a
                // different UA than the page it came from would be a second,
                // contradictory identity from the same "handset".
                entity.userAgent.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            }
            .build()
        return client.newBuilder().followRedirects(true).build().newCall(request).execute()
    }

    /** Bytes per second since the last sample, published for the UI. Null until
     *  a full second has passed — a rate measured over 40 ms is noise. */
    private fun sampleSpeed(id: Long, downloaded: Long, now: Long, sampledAt: Long, sampledBytes: Long): Long? {
        val elapsed = now - sampledAt
        if (elapsed < 1_000L) return null
        val speed = ((downloaded - sampledBytes) * 1000L) / elapsed
        _speeds.value = _speeds.value + (id to speed.coerceAtLeast(0L))
        return speed
    }

    /**
     * Records how a transfer ended — but only if the row is still ours.
     *
     * The RUNNING check is what makes this safe to run late. By the time a
     * cancelled job reaches here the row may already have been rewritten by
     * `pause` (PAUSED), `cancel` (CANCELLED, or gone entirely after a delete),
     * or `resume` (QUEUED for a fresh attempt). Writing this job's outcome over
     * any of those is exactly how a paused download used to end up filed as
     * FAILED.
     */
    private suspend fun recordFailure(entity: DownloadEntity, e: Throwable) {
        val row = repo.download(entity.id)
        if (row == null || row.status != DownloadStatus.RUNNING.name) {
            _speeds.value = _speeds.value - entity.id
            return
        }
        val status = when {
            entity.id in cancelled -> DownloadStatus.CANCELLED
            entity.id in paused -> DownloadStatus.PAUSED
            e is CancelledException -> DownloadStatus.CANCELLED
            else -> DownloadStatus.FAILED
        }
        val message = if (status == DownloadStatus.FAILED) (e.message ?: "download failed") else null
        repo.updateDownloadStatus(entity.id, status.name, message)
        _speeds.value = _speeds.value - entity.id
        when (status) {
            DownloadStatus.PAUSED ->
                notifyPaused(entity.id, entity.fileName, row.downloadedBytes, row.totalBytes)
            DownloadStatus.CANCELLED -> dismiss(entity.id)
            else -> notifyFailure(entity.id, entity.fileName, message)
        }
    }

    private class CancelledException : IOException("cancelled")

    /** Duplicate filename handling: append " (n)" before extension. */
    private suspend fun dedupeName(profileId: ProfileId, name: String): String {
        val existing = repo.downloadsFor(profileId).map { it.fileName }.toSet()
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    /**
     * Move the finished file into shared Downloads storage.
     * API 29+: MediaStore.Downloads with RELATIVE_PATH Download/<subfolder>.
     * API 28:  legacy public Downloads directory.
     */
    private fun publish(entity: DownloadEntity, partFile: File): String {
        val subfolder = "RoomBrowser"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entity.fileName)
                put(MediaStore.Downloads.MIME_TYPE, entity.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subfolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                partFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Could not open output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            partFile.delete()
            uri.toString()
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subfolder)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, entity.fileName)
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
            Uri.fromFile(target).toString()
        }
    }

    /**
     * Publish bytes the app produced itself -- a rendered page PDF -- through
     * the same Downloads storage a retrieved file uses, so it appears in the
     * browser's own downloads list. [source] is read on this engine's IO scope
     * and closed here; a source past [limitBytes] is refused, not written.
     */
    fun saveStream(
        suggestedName: String,
        mime: String,
        source: InputStream,
        limitBytes: Long,
        onResult: (Boolean) -> Unit
    ) {
        scope.launch {
            val entity = DownloadEntity(
                profileId = profileId.value,
                url = "",
                fileName = dedupeName(profileId, suggestedName),
                mimeType = mime.ifBlank { "application/octet-stream" },
                destination = "",
                totalBytes = -1,
                downloadedBytes = 0,
                status = DownloadStatus.RUNNING.name,
                createdAt = System.currentTimeMillis()
            )
            var id = 0L
            var ok = false
            try {
                id = repo.insertDownload(entity)
                val partFile = partFileFor(id).apply { parentFile?.mkdirs() }
                val size = source.use { input ->
                    FileOutputStream(partFile).use { out -> copyCapped(input, out, limitBytes) }
                }
                val destination = publish(entity, partFile)
                repo.completeDownload(
                    id = id,
                    status = DownloadStatus.COMPLETED.name,
                    destination = destination,
                    downloaded = size,
                    total = size,
                    completedAt = System.currentTimeMillis()
                )
                notifyDone(id, entity.fileName, destination)
                ok = true
            } catch (e: Throwable) {
                runCatching { source.close() }
                if (id != 0L) {
                    // A capped or interrupted save leaves a `.part` behind, and
                    // nothing else in the app ever looks at it again.
                    runCatching { partFileFor(id).delete() }
                    val message = e.message ?: "save failed"
                    repo.updateDownloadStatus(id, DownloadStatus.FAILED.name, message)
                    notifyFailure(id, entity.fileName, message)
                }
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** Copies [input] to [out], refusing a source past [limit] bytes. */
    private fun copyCapped(input: InputStream, out: FileOutputStream, limit: Long): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw IOException("file too large")
            out.write(buffer, 0, read)
        }
        return total
    }

    /** Removes a published file, whether MediaStore owns it or the filesystem does. */
    private fun deletePublished(destination: String) {
        val uri = destination.toUri()
        when (uri.scheme) {
            "content" -> context.contentResolver.delete(uri, null, null)
            "file" -> uri.path?.let { File(it).delete() }
            else -> Unit
        }
    }

    fun open(id: Long) {
        scope.launch {
            val dl = repo.download(id) ?: return@launch
            if (dl.status != DownloadStatus.COMPLETED.name) return@launch
            val uri = dl.destination.toUri()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, dl.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, dl.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun share(id: Long) {
        scope.launch {
            val dl = repo.download(id) ?: return@launch
            if (dl.status != DownloadStatus.COMPLETED.name) return@launch
            val uri = dl.destination.toUri()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = dl.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun baseNotification(id: Long): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    private fun notifyProgress(id: Long, name: String, downloaded: Long, total: Long, speed: Long?) {
        ensureChannels()
        val builder = baseNotification(id)
            .setContentTitle(name)
            .setContentText(progressLine(downloaded, total, speed))
            .setProgress(100, DownloadFormat.percent(downloaded, total) ?: 0, total <= 0)
            .addAction(android.R.drawable.ic_media_pause, "Pause", actionIntent(id, "pause"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", actionIntent(id, "cancel"))
        post(id, builder.build())
    }

    /** A paused transfer keeps its notification, but offers the way out of it. */
    private fun notifyPaused(id: Long, name: String, downloaded: Long, total: Long) {
        ensureChannels()
        val builder = baseNotification(id)
            .setContentTitle(name)
            .setContentText("Paused · " + sizeLine(downloaded, total))
            .setProgress(100, DownloadFormat.percent(downloaded, total) ?: 0, total <= 0)
            .addAction(android.R.drawable.ic_media_play, "Resume", actionIntent(id, "resume"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", actionIntent(id, "cancel"))
        post(id, builder.build())
    }

    private fun progressLine(downloaded: Long, total: Long, speed: Long?): String {
        val parts = mutableListOf(sizeLine(downloaded, total))
        DownloadFormat.percent(downloaded, total)?.let { parts.add("$it%") }
        DownloadFormat.speed(speed ?: 0).let { if (it != null) parts.add(it) }
        return parts.joinToString(" · ")
    }

    private fun sizeLine(downloaded: Long, total: Long): String =
        if (total > 0) "${DownloadFormat.bytes(downloaded)} / ${DownloadFormat.bytes(total)}"
        else "${DownloadFormat.bytes(downloaded)} downloaded"

    private fun notifyDone(id: Long, name: String, destination: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name)
            .setContentText("Download complete")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(id, destination))
            .addAction(0, "Open", actionIntent(id, "open"))
        post(id, builder.build())
    }

    private fun notifyFailure(id: Long, name: String, message: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(name)
            .setContentText("Download failed${message?.let { ": $it" } ?: ""}")
            .setAutoCancel(true)
            .addAction(0, "Retry", actionIntent(id, "retry"))
        post(id, builder.build())
    }

    private fun post(id: Long, notification: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_TAG, id.toInt(), notification) }
    }

    private fun dismiss(id: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIF_TAG, id.toInt()) }
    }

    /**
     * Tapping a finished notification opens the file directly, which needs the
     * VIEW intent to be carried by an activity PendingIntent rather than the
     * broadcast the action buttons use.
     */
    private fun contentIntent(id: Long, destination: String): PendingIntent? = runCatching {
        val uri = destination.toUri()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(
            context,
            id.toInt(),
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private fun actionIntent(id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id * 31 + action.hashCode()).toInt(),
        Intent(ACTION).apply {
            setPackage(context.packageName)
            putExtra("id", id)
            putExtra("action", action)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun shutdown() {
        if (current === this) current = null
        scope.cancel()
    }
}
