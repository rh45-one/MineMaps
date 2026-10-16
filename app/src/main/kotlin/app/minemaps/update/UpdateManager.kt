package app.minemaps.update

import android.content.Context
import android.content.pm.PackageInstaller
import app.minemaps.BuildConfig
import app.minemaps.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    class Downloading(val info: ReleaseInfo, val bytes: Long) : UpdateUi
    class Verifying(val info: ReleaseInfo) : UpdateUi
    class NeedsPermission(val info: ReleaseInfo) : UpdateUi
    class Installing(val info: ReleaseInfo) : UpdateUi
    class Failed(val message: String) : UpdateUi
}

class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val client: suspend () -> OkHttpClient,
    private val installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
    apiBase: String = BuildConfig.UPDATE_API_BASE,
    repo: String = BuildConfig.UPDATE_REPO,
    private val userAgent: String = BuildConfig.USER_AGENT,
    val enabled: Boolean = BuildConfig.UPDATES_ENABLED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val checker = UpdateChecker(client, apiBase, repo, userAgent)
    private val allowHttp = !apiBase.startsWith("https://")
    private val autoChecked = AtomicBoolean(false)
    private var job: Job? = null

    val state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val offer = MutableStateFlow<ReleaseInfo?>(null)
    val dialogVisible = MutableStateFlow(false)

    private val updatesDir get() = File(context.cacheDir, "updates")

    init {
        scope.launch {
            UpdateEvents.status.collect { (status, message) ->
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {}
                    PackageInstaller.STATUS_SUCCESS -> cleanup().also { state.value = UpdateUi.Idle }
                    else -> {
                        cleanup()
                        state.value = UpdateUi.Failed(message?.takeIf { it.isNotBlank() } ?: "Installation failed")
                    }
                }
            }
        }
    }

    fun checkIfDue() {
        if (!enabled || !autoChecked.compareAndSet(false, true)) return
        scope.launch {
            cleanup()
            val s = settings.settings.first()
            if (UpdatePolicy.isDue(s.updateFrequency, s.lastUpdateCheck, clock())) check(manual = false)
        }
    }

    fun checkNow() {
        if (!enabled || state.value is UpdateUi.Checking) return
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        state.value = UpdateUi.Checking
        val s = settings.settings.first()
        val now = clock()
        when (val r = checker.check(installedCode, s.skippedVersionCode, manual)) {
            is CheckResult.Available -> {
                offer.value = r.info
                dialogVisible.value = true
                settings.setUpdateCheck(now, "Update available: ${r.info.tag}")
            }
            CheckResult.UpToDate -> {
                offer.value = null
                settings.setUpdateCheck(now, "Up to date")
            }
            is CheckResult.Failed -> settings.setUpdateCheck(if (r.responded) now else null, r.message)
        }
        state.value = UpdateUi.Idle
    }

    fun later() {
        dialogVisible.value = false
    }

    fun skip() {
        val info = offer.value ?: return
        dialogVisible.value = false
        scope.launch { settings.setSkippedVersion(info.versionCode) }
    }

    fun showOffer() {
        if (offer.value != null) dialogVisible.value = true
    }

    fun dismissFailure() {
        if (state.value is UpdateUi.Failed) state.value = UpdateUi.Idle
    }

    fun cancel() {
        job?.cancel()
        job = null
        cleanup()
        state.value = UpdateUi.Idle
    }

    fun install() {
        val info = offer.value ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val http = client()
                cleanup()
                val apk = File(updatesDir, "update.apk")
                state.value = UpdateUi.Downloading(info, 0)
                UpdateInstaller.download(http, info.apkUrl, apk, info.apkSize, allowHttp, userAgent) { state.value = UpdateUi.Downloading(info, it) }
                state.value = UpdateUi.Verifying(info)
                val expected = Sha256File.parse(UpdateInstaller.fetchText(http, info.sha256Url, userAgent)) ?: throw UpdateException("The checksum file is not valid")
                val actual = UpdateInstaller.sha256Hex(apk)
                if (!actual.equals(expected, ignoreCase = true)) throw UpdateException("Checksum mismatch: the download was corrupted or tampered with")
                UpdateInstaller.verifyApk(context, apk, installedCode)?.let { throw UpdateException(it) }
                if (!UpdateInstaller.canInstall(context)) {
                    state.value = UpdateUi.NeedsPermission(info)
                    context.startActivity(UpdateInstaller.unknownSourcesIntent(context))
                    var waited = 0
                    while (!UpdateInstaller.canInstall(context)) {
                        delay(1000)
                        if (++waited > 180) throw UpdateException("Installing from this app was not allowed")
                    }
                }
                state.value = UpdateUi.Installing(info)
                UpdateInstaller.install(context, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanup()
                state.value = UpdateUi.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Update failed")
            }
        }
    }

    private fun cleanup() {
        runCatching { updatesDir.deleteRecursively() }
    }
}
