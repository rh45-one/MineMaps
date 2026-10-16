package app.minemaps.update

import app.minemaps.data.UpdateFrequency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class Semver(val major: Int, val minor: Int, val patch: Int, val rc: Int?) {
    val versionCode: Int get() = major * 1_000_000 + minor * 1_000 + patch

    override fun toString() = "$major.$minor.$patch" + (rc?.let { "-rc.$it" } ?: "")

    companion object {
        private val pattern = Regex("""^(\d{1,4})\.(\d{1,3})\.(\d{1,3})(?:-rc\.(\d{1,3}))?$""")

        fun parse(text: String): Semver? {
            val m = pattern.matchEntire(text.trim().removePrefix("v")) ?: return null
            val (a, b, c) = listOf(1, 2, 3).map { m.groupValues[it].toInt() }
            if (a > 2000 || b > 999 || c > 999) return null
            return Semver(a, b, c, m.groups[4]?.value?.toInt())
        }
    }
}

class ReleaseInfo(
    val version: Semver,
    val htmlUrl: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String,
) {
    val tag: String get() = "v$version"
    val versionCode: Int get() = version.versionCode
}

object ReleaseParser {
    const val MAX_JSON_CHARS = 1_000_000
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    private const val MAX_NOTES = 1200
    private val json = Json { ignoreUnknownKeys = true }

    /** Null for malformed, draft, prerelease or incomplete releases. [allowHttp] is for local test servers only. */
    fun parse(text: String, allowHttp: Boolean = false): ReleaseInfo? {
        if (text.length > MAX_JSON_CHARS) return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if ((root["prerelease"] as? JsonPrimitive)?.booleanOrNull == true || (root["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val version = Semver.parse(str(root, "tag_name") ?: return null) ?: return null
        if (version.rc != null) return null
        val html = str(root, "html_url")?.takeIf { okUrl(it, allowHttp) } ?: ""
        val assets = (root["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val apkName = "MineMaps-v$version.apk"
        val apk = assets.firstOrNull { str(it, "name") == apkName } ?: return null
        val sha = assets.firstOrNull { str(it, "name") == "$apkName.sha256" } ?: return null
        val apkUrl = str(apk, "browser_download_url")?.takeIf { okUrl(it, allowHttp) } ?: return null
        val shaUrl = str(sha, "browser_download_url")?.takeIf { okUrl(it, allowHttp) } ?: return null
        val size = (apk["size"] as? JsonPrimitive)?.longOrNull ?: return null
        if (size <= 0 || size > MAX_APK_BYTES) return null
        val notes = (str(root, "body") ?: "").replace("\r", "").trim().take(MAX_NOTES)
        return ReleaseInfo(version, html, notes, apkUrl, size, shaUrl)
    }

    private fun okUrl(u: String, allowHttp: Boolean) = u.startsWith("https://") || (allowHttp && u.startsWith("http://"))
}

object UpdatePolicy {
    private const val DAY = 24L * 3600 * 1000

    fun isDue(freq: UpdateFrequency, lastCheck: Long, now: Long): Boolean = when (freq) {
        UpdateFrequency.MANUAL -> false
        UpdateFrequency.LAUNCH -> true
        UpdateFrequency.DAILY -> lastCheck <= 0 || lastCheck > now || now - lastCheck >= DAY
        UpdateFrequency.WEEKLY -> lastCheck <= 0 || lastCheck > now || now - lastCheck >= 7 * DAY
    }

    fun isNewer(info: ReleaseInfo, installedCode: Long): Boolean = info.versionCode > installedCode

    fun shouldOffer(info: ReleaseInfo, installedCode: Long, skippedCode: Int, manual: Boolean): Boolean =
        isNewer(info, installedCode) && (manual || info.versionCode != skippedCode)
}

object Sha256File {
    private val hex = Regex("^[0-9a-fA-F]{64}$")

    /** `sha256sum` format ("<hex>  <name>") or a bare digest. */
    fun parse(text: String): String? = text.trim().lineSequence().firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { hex.matches(it) }?.lowercase()
}
