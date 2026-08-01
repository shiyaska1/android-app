package com.billing.pos.sync

import android.content.Context
import android.net.Uri
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.FullBackup
import com.billing.pos.data.License
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Background cloud sync: while enabled, on a timer, pulls the server's backup, merges it into
 * local data, then pushes the merged result back — data and settings only, never attachments,
 * so each round stays small and doesn't depend on the server tolerating a large upload. Runs in
 * a process-wide scope, independent of which screen is open (started from Settings and re-armed
 * on app launch if left on).
 */
object CloudSyncManager {

    val status = MutableStateFlow("Idle")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoJob: Job? = null
    private val gate = Mutex()

    fun isRunning(): Boolean = autoJob != null

    fun startAuto(context: Context, intervalMs: Long) {
        stopAuto()
        val app = context.applicationContext
        autoJob = scope.launch {
            while (isActive) {
                runOnePullMergePush(app)
                delay(intervalMs)
            }
        }
    }

    fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
    }

    /** One pull -> merge -> push cycle. Safe to call directly too (e.g. a "Sync now" button). */
    suspend fun runOnePullMergePush(app: Context): Boolean = gate.withLock {
        val prefs = AppPrefs(app)
        val pullUrl = prefs.backupPullUrl
        val pushUrl = prefs.backupPushUrl
        if (pullUrl.isBlank() || pushUrl.isBlank()) {
            status.value = "Set Push/Pull URL in Settings first"
            return@withLock false
        }
        try {
            val org = prefs.backupOrgId.filter { it.isLetterOrDigit() }.ifBlank { "org" }
            val dev = prefs.backupDeviceId.ifBlank { License.deviceId(app) }
                .filter { it.isLetterOrDigit() }.ifBlank { "device" }
            fun withOrgDevice(base: String): String {
                val sep = if (base.contains("?")) "&" else "?"
                return "$base${sep}org=$org&device=$dev"
            }

            // --- Pull + merge (skip cleanly if nothing has ever been pushed for this org/device) ---
            val pullConn = URL(withOrgDevice(pullUrl)).openConnection() as HttpURLConnection
            pullConn.requestMethod = "GET"
            pullConn.connectTimeout = 15000
            pullConn.readTimeout = 60000
            val pullCode = pullConn.responseCode
            when (pullCode) {
                200 -> {
                    val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                    val dest = File(dir, "autosync-pulled.zip")
                    pullConn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    pullConn.disconnect()
                    val valid = runCatching { ZipFile(dest).use { it.size() } }.isSuccess
                    if (valid) {
                        FullBackup.restore(app, Uri.fromFile(dest), merge = true).onFailure {
                            status.value = "Auto-sync: merge failed (${it.message}), pushing anyway"
                        }
                    } else {
                        status.value = "Auto-sync: downloaded backup was corrupt, skipped merge"
                    }
                }
                404 -> pullConn.disconnect() // first cycle ever for this org/device — nothing to merge yet
                else -> {
                    pullConn.disconnect()
                    status.value = "Auto-sync: pull failed (HTTP $pullCode), skipped this cycle"
                    return@withLock false
                }
            }

            // --- Push (data only, no attachments) ---
            val zip = FullBackup.create(app, includeAttachments = false)
            if (runCatching { ZipFile(zip).use { it.size() } }.isFailure) {
                status.value = "Auto-sync: local backup came out corrupt, skipped push"
                return@withLock false
            }
            val pushConn = URL(withOrgDevice(pushUrl)).openConnection() as HttpURLConnection
            pushConn.requestMethod = "POST"
            pushConn.doOutput = true
            pushConn.connectTimeout = 15000
            pushConn.readTimeout = 60000
            pushConn.setRequestProperty("Content-Type", "application/zip")
            val expectedLen = zip.length()
            pushConn.setFixedLengthStreamingMode(expectedLen)
            val sent = pushConn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }
            val pushCode = pushConn.responseCode
            pushConn.disconnect()
            if (sent != expectedLen || pushCode !in 200..299) {
                status.value = "Auto-sync: push failed (HTTP $pushCode)"
                return@withLock false
            }
            status.value = "Auto-synced at " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            true
        } catch (e: Exception) {
            status.value = "Auto-sync failed: ${e.message}"
            false
        }
    }
}
