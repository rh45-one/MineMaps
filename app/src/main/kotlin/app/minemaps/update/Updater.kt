package app.minemaps.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface CheckResult {
    data object UpToDate : CheckResult
    class Available(val info: ReleaseInfo) : CheckResult
    class Failed(val message: String, val responded: Boolean) : CheckResult
}

class UpdateException(message: String) : IOException(message)

class UpdateChecker(
    private val client: suspend () -> OkHttpClient,
    private val apiBase: String,
    private val repo: String,
    private val userAgent: String,
) {
    private val allowHttp = !apiBase.startsWith("https://")

    suspend fun check(installedCode: Long, skippedCode: Int, manual: Boolean): CheckResult = withContext(Dispatchers.IO) {
        val url = "${apiBase.trimEnd('/')}/repos/$repo/releases/latest"
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", userAgent).build()
        try {
            val http = client().newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            http.newCall(request).execute().use { r ->
                if (r.code == 404) return@use CheckResult.UpToDate
                if (!r.isSuccessful) return@use CheckResult.Failed(if (r.code == 403 || r.code == 429) "Rate limited by GitHub, try later" else "GitHub answered HTTP ${r.code}", true)
                val body = r.body
                if (body.contentLength() > ReleaseParser.MAX_JSON_CHARS) return@use CheckResult.Failed("Response too large", true)
                val source = body.source()
                val buffer = okio.Buffer()
                while (buffer.size <= ReleaseParser.MAX_JSON_CHARS) if (source.read(buffer, 8192) < 0) break
                if (buffer.size > ReleaseParser.MAX_JSON_CHARS) return@use CheckResult.Failed("Response too large", true)
                val info = ReleaseParser.parse(buffer.readUtf8(), allowHttp) ?: return@use CheckResult.UpToDate
                if (UpdatePolicy.shouldOffer(info, installedCode, skippedCode, manual)) CheckResult.Available(info) else CheckResult.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            CheckResult.Failed("Network error", false)
        } catch (e: RuntimeException) {
            CheckResult.Failed("Unexpected response", false)
        }
    }
}

object UpdateInstaller {
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(client: OkHttpClient, url: String, dest: File, maxBytes: Long, allowHttp: Boolean, userAgent: String, onProgress: (Long) -> Unit) {
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            val http = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw UpdateException("Download failed (HTTP ${r.code})")
                if (!allowHttp && r.request.url.scheme != "https") throw UpdateException("Download was not served over HTTPS")
                val len = r.body.contentLength()
                if (len > maxBytes) throw UpdateException("Download is larger than announced")
                var total = 0L
                r.body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw UpdateException("Download is larger than announced")
                            out.write(buf, 0, n)
                            onProgress(total)
                        }
                    }
                }
                if (total != maxBytes) throw UpdateException("Download size does not match the release")
            }
        }
    }

    suspend fun fetchText(client: OkHttpClient, url: String, userAgent: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException("Checksum download failed (HTTP ${r.code})")
            val src = r.body.source()
            src.request(4096)
            src.buffer.readUtf8(minOf(src.buffer.size, 4096))
        }
    }

    @Suppress("DEPRECATION")
    private fun digests(signatures: Array<Signature>?): Set<String> = signatures.orEmpty().map { s ->
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()

    @Suppress("DEPRECATION")
    private fun signerDigests(pm: PackageManager, info: android.content.pm.PackageInfo?): Set<String> {
        info ?: return emptySet()
        return if (Build.VERSION.SDK_INT >= 28) digests(info.signingInfo?.apkContentsSigners) else digests(info.signatures)
    }

    /** The APK must be this app, newer, and signed with the same certificate(s) as the installed one. Returns an error message, or null when OK. */
    @Suppress("DEPRECATION")
    fun verifyApk(context: Context, file: File, installedCode: Long): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "The downloaded file is not a valid APK"
        if (archive.packageName != context.packageName) return "The APK is for a different app"
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (code <= installedCode) return "The APK is not newer than the installed version"
        val installed = signerDigests(pm, pm.getPackageInfo(context.packageName, flags))
        val offered = signerDigests(pm, archive)
        if (installed.isEmpty() || offered.isEmpty() || installed != offered) return "The APK is signed with a different key; it cannot update this install"
        return null
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("update.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

object UpdateEvents {
    val status = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Int, String?>>(extraBufferCapacity = 4)
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
        UpdateEvents.status.tryEmit(status to intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
