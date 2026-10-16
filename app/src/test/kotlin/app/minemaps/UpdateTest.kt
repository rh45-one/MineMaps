package app.minemaps

import app.minemaps.data.UpdateFrequency
import app.minemaps.update.ReleaseParser
import app.minemaps.update.Semver
import app.minemaps.update.Sha256File
import app.minemaps.update.UpdatePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    private fun release(tag: String = "v1.2.0", prerelease: Boolean = false, assets: String? = null, size: Long = 1000, body: String = "Notes"): String {
        val v = tag.removePrefix("v")
        val a = assets ?: """[
            {"name":"MineMaps-v$v.apk","browser_download_url":"https://github.com/o/r/releases/download/$tag/MineMaps-v$v.apk","size":$size},
            {"name":"MineMaps-v$v.apk.sha256","browser_download_url":"https://github.com/o/r/releases/download/$tag/MineMaps-v$v.apk.sha256","size":90}]"""
        return """{"tag_name":"$tag","html_url":"https://github.com/o/r/releases/tag/$tag","prerelease":$prerelease,"draft":false,"body":"$body","assets":$a}"""
    }

    @Test
    fun semverParsingAndVersionCode() {
        assertEquals(1_002_003, Semver.parse("1.2.3")!!.versionCode)
        assertEquals(1_002_003, Semver.parse("v1.2.3")!!.versionCode)
        assertEquals(1_002_003, Semver.parse("1.2.3-rc.4")!!.versionCode)
        assertEquals(4, Semver.parse("1.2.3-rc.4")!!.rc)
        assertEquals("0.10.0", Semver.parse("0.10.0")!!.toString())
        for (bad in listOf("", "1.2", "1.2.3.4", "1.2.x", "a.b.c", "1.2.3-beta", "1.2.3-rc", "-1.2.3", "99999.0.0", "1.1000.0", "1.2.3 ", "1.2.3+dev")) {
            if (bad == "1.2.3 ") assertNotNull(Semver.parse(bad)) else assertNull(Semver.parse(bad), bad)
        }
        assertTrue(Semver.parse("1.10.0")!!.versionCode > Semver.parse("1.9.9")!!.versionCode)
        assertTrue(Semver.parse("2.0.0")!!.versionCode > Semver.parse("1.999.999")!!.versionCode)
    }

    @Test
    fun parsesAValidRelease() {
        val r = ReleaseParser.parse(release())!!
        assertEquals("v1.2.0", r.tag)
        assertEquals(1_002_000, r.versionCode)
        assertEquals("Notes", r.notes)
        assertEquals(1000, r.apkSize)
        assertTrue(r.apkUrl.endsWith("MineMaps-v1.2.0.apk"))
        assertTrue(r.sha256Url.endsWith(".apk.sha256"))
    }

    @Test
    fun rejectsPrereleasesMalformedAndIncompleteReleases() {
        assertNull(ReleaseParser.parse(release(prerelease = true)))
        assertNull(ReleaseParser.parse(release(tag = "v1.2.0-rc.1")))
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse("[]"))
        assertNull(ReleaseParser.parse("""{"tag_name":"latest","assets":[]}"""))
        assertNull(ReleaseParser.parse(release(assets = "[]")))
        assertNull(ReleaseParser.parse(release(assets = """[{"name":"MineMaps-v1.2.0.apk","browser_download_url":"https://x/y.apk","size":10}]""")))
        assertNull(ReleaseParser.parse(release(assets = """[{"name":"MineMaps-v1.2.0.apk","browser_download_url":"http://x/y.apk","size":10},{"name":"MineMaps-v1.2.0.apk.sha256","browser_download_url":"https://x/y.sha256","size":10}]""")))
        assertNull(ReleaseParser.parse(release(assets = """[{"name":"Other.apk","browser_download_url":"https://x/y.apk","size":10},{"name":"Other.apk.sha256","browser_download_url":"https://x/y.sha256","size":10}]""")))
        assertNull(ReleaseParser.parse(release(size = 0)))
        assertNull(ReleaseParser.parse(release(size = ReleaseParser.MAX_APK_BYTES + 1)))
        assertNull(ReleaseParser.parse("{" + "\"a\":1,".repeat(200_000) + "\"tag_name\":\"v1.0.0\"}"))
    }

    @Test
    fun httpAssetsAreOnlyAcceptedForLocalTestServers() {
        val a = """[{"name":"MineMaps-v1.2.0.apk","browser_download_url":"http://127.0.0.1:8080/a.apk","size":10},{"name":"MineMaps-v1.2.0.apk.sha256","browser_download_url":"http://127.0.0.1:8080/a.sha256","size":10}]"""
        assertNull(ReleaseParser.parse(release(assets = a)))
        assertNotNull(ReleaseParser.parse(release(assets = a), allowHttp = true))
    }

    @Test
    fun hugeNotesAreTruncated() {
        val r = ReleaseParser.parse(release(body = "x".repeat(50_000)))!!
        assertTrue(r.notes.length <= 1200)
    }

    @Test
    fun dueChecksPerFrequency() {
        val day = 24L * 3600 * 1000
        val now = 100 * day
        assertTrue(UpdatePolicy.isDue(UpdateFrequency.LAUNCH, now, now))
        assertFalse(UpdatePolicy.isDue(UpdateFrequency.MANUAL, 0, now))
        assertTrue(UpdatePolicy.isDue(UpdateFrequency.DAILY, 0, now))
        assertFalse(UpdatePolicy.isDue(UpdateFrequency.DAILY, now - day + 1000, now))
        assertTrue(UpdatePolicy.isDue(UpdateFrequency.DAILY, now - day, now))
        assertFalse(UpdatePolicy.isDue(UpdateFrequency.WEEKLY, now - 6 * day, now))
        assertTrue(UpdatePolicy.isDue(UpdateFrequency.WEEKLY, now - 7 * day, now))
        assertTrue(UpdatePolicy.isDue(UpdateFrequency.DAILY, now + day, now))
    }

    @Test
    fun onlyStrictlyNewerVersionsAreOfferedAndSkipIsHonouredForAutomaticChecks() {
        val r = ReleaseParser.parse(release(tag = "v1.2.0"))!!
        assertTrue(UpdatePolicy.shouldOffer(r, 1_001_999, 0, manual = false))
        assertFalse(UpdatePolicy.shouldOffer(r, 1_002_000, 0, manual = true))
        assertFalse(UpdatePolicy.shouldOffer(r, 1_003_000, 0, manual = true))
        assertFalse(UpdatePolicy.shouldOffer(r, 1_001_000, r.versionCode, manual = false))
        assertTrue(UpdatePolicy.shouldOffer(r, 1_001_000, r.versionCode, manual = true))
    }

    @Test
    fun sha256FileParsing() {
        val h = "a".repeat(64)
        assertEquals(h, Sha256File.parse("$h  MineMaps-v1.2.0.apk\n"))
        assertEquals(h, Sha256File.parse(h.uppercase()))
        assertNull(Sha256File.parse(""))
        assertNull(Sha256File.parse("abc  file"))
        assertNull(Sha256File.parse("g".repeat(64)))
        assertNull(Sha256File.parse("<html>" + h))
    }
}
