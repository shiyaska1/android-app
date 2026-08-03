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
import kotlinx.coroutines.withContext
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
    /** True for the duration of an actual pull/merge/push cycle — drives the global "Syncing…" banner. */
    val isSyncing = MutableStateFlow(false)

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

    private fun hasInternet(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** One pull -> merge -> push cycle. Safe to call directly too (e.g. a "Sync now" button). */
    suspend fun runOnePullMergePush(app: Context): Boolean = runCycle(app, forceFullPush = false)

    /** Same cycle, but ignores [AppPrefs.cloudPushWindowDays] for this one push — for a "Full
     * resync now" button, so a new device (or one that's been off the window too long) can be
     * brought fully up to date without turning the window off in Settings first. */
    suspend fun runFullResync(app: Context): Boolean = runCycle(app, forceFullPush = true)

    /** Records what happened so the user can check later, without needing to catch a toast. */
    private fun logResult(app: Context, ok: Boolean) {
        val prefs = AppPrefs(app)
        prefs.lastCloudSyncAt = System.currentTimeMillis()
        prefs.lastCloudSyncOk = ok
        prefs.lastCloudSyncMessage = status.value
    }

    // withContext(IO) makes this safe regardless of which dispatcher the caller is on — the
    // manual "Sync now" button launches from rememberCoroutineScope() (Main), while startAuto's
    // loop already runs on an IO-bound scope; without this, the manual path threw
    // NetworkOnMainThreadException on the raw HttpURLConnection calls below.
    private suspend fun runCycle(app: Context, forceFullPush: Boolean): Boolean = withContext(Dispatchers.IO) { gate.withLock {
        val prefs = AppPrefs(app)
        val pullUrl = prefs.backupPullUrl
        val pushUrl = prefs.backupPushUrl
        if (pullUrl.isBlank() || pushUrl.isBlank()) {
            status.value = "Set Push/Pull URL in Settings first"
            logResult(app, false)
            return@withLock false
        }
        if (!hasInternet(app)) {
            status.value = "No internet connection — skipped this sync"
            logResult(app, false)
            return@withLock false
        }
        isSyncing.value = true
        var ok = false
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
            val windowDays = if (forceFullPush) 0 else prefs.cloudPushWindowDays
            val zip = FullBackup.create(app, includeAttachments = false, pushWindowDays = windowDays)
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
            ok = true
            true
        } catch (e: Exception) {
            // e.message is often null for plain NPEs/IO edge cases — the exception's own class
            // name still says something ("MalformedURLException" vs "SocketTimeoutException")
            // instead of the previous unhelpful "Auto-sync failed: null".
            status.value = "Auto-sync failed: ${e.javaClass.simpleName}" + (e.message?.let { " — $it" } ?: "")
            false
        } finally {
            isSyncing.value = false
            logResult(app, ok)
        }
    } }
}
