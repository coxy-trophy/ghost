package com.coxy.ghost.action

import com.coxy.ghost.action.AppMatcher.AppEntry
import com.coxy.ghost.action.AppMatcher.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching is tested against the apps genuinely installed on the RMX3760, not against invented
 * ones, because the awkward cases are all real: "Meet" is `com.google.android.apps.tachyon`,
 * "Gmail" is `com.google.android.gm`, the launcher is `ginlemon.flowerfree`, and two packages are
 * obfuscated strings. A fixture of `com.example.spotify`-style names would pass every test here and
 * prove nothing about the device this runs on.
 */
class AppMatcherTest {

    private val installed = listOf(
        AppEntry("com.absa.gh.mobile.android.ui", "Absa"),
        AppEntry("com.activision.callofduty.shooter", "Call of Duty"),
        AppEntry("com.android.camera2", "Camera"),
        AppEntry("com.android.chrome", "Chrome"),
        AppEntry("com.android.settings", "Settings"),
        AppEntry("com.app.ecobank", "Ecobank"),
        AppEntry("com.coloros.filemanager", "File Manager"),
        AppEntry("com.coloros.gallery3d", "Photos"),
        AppEntry("com.coloros.weather2", "Weather"),
        AppEntry("com.coxy.ghost", "GHOST"),
        AppEntry("com.google.android.apps.maps", "Maps"),
        AppEntry("com.google.android.apps.messaging", "Messages"),
        AppEntry("com.google.android.apps.nbu.files", "Files"),
        AppEntry("com.google.android.apps.tachyon", "Meet"),
        AppEntry("com.google.android.apps.youtube.music", "YouTube Music"),
        AppEntry("com.google.android.dialer", "Phone"),
        AppEntry("com.google.android.gm", "Gmail"),
        AppEntry("com.google.android.googlequicksearchbox", "Google"),
        AppEntry("com.google.android.youtube", "YouTube"),
        AppEntry("com.openai.chatgpt", "ChatGPT"),
        AppEntry("com.spotify.music", "Spotify"),
        AppEntry("com.termux", "Termux"),
        AppEntry("com.whatsapp", "WhatsApp"),
        AppEntry("ginlemon.flowerfree", "Smart Launcher"),
        AppEntry("org.telegram.messenger", "Telegram"),
        AppEntry("org.videolan.vlc", "VLC"),
    )

    private fun pkgFor(query: String): String {
        val r = AppMatcher.resolve(query, installed)
        assertTrue("expected a single match for '$query', got $r", r is Resolution.Match)
        return (r as Resolution.Match).entry.pkg
    }

    // --- the cases the model could never have guessed ------------------------------------------

    @Test
    fun `labels that share nothing with their package still resolve`() {
        assertEquals("com.google.android.apps.tachyon", pkgFor("Meet"))
        assertEquals("com.google.android.gm", pkgFor("Gmail"))
        assertEquals("ginlemon.flowerfree", pkgFor("Smart Launcher"))
        assertEquals("com.coloros.gallery3d", pkgFor("Photos"))
    }

    @Test
    fun `case and spacing are not identity`() {
        assertEquals("com.whatsapp", pkgFor("whatsapp"))
        assertEquals("com.whatsapp", pkgFor("WhatsApp"))
        assertEquals("com.whatsapp", pkgFor("whats app"))
        assertEquals("com.coloros.filemanager", pkgFor("filemanager"))
        assertEquals("com.google.android.apps.youtube.music", pkgFor("youtube music"))
    }

    @Test
    fun `the words people pad a request with are ignored`() {
        assertEquals("com.spotify.music", pkgFor("the spotify app"))
        assertEquals("com.android.camera2", pkgFor("my camera"))
        assertEquals("org.telegram.messenger", pkgFor("open telegram"))
    }

    // --- backwards compatibility: a package still works ----------------------------------------

    @Test
    fun `an exact package still resolves, so a model that knows one is not punished`() {
        assertEquals("com.spotify.music", pkgFor("com.spotify.music"))
        assertEquals("com.google.android.apps.tachyon", pkgFor("com.google.android.apps.tachyon"))
    }

    @Test
    fun `the last segment of a package is a fallback name`() {
        // The app is labelled "Phone"; "dialer" only exists in the package. Nothing else answers.
        assertEquals("com.google.android.dialer", pkgFor("dialer"))
    }

    @Test
    fun `a generic package segment never outranks a real label`() {
        // com.spotify.music ends in "music". Before the tier order was fixed that made "music"
        // resolve silently to Spotify, with YouTube Music sitting right there in the list.
        assertEquals("com.google.android.apps.youtube.music", pkgFor("music"))
    }

    // --- what must NOT happen -------------------------------------------------------------------

    @Test
    fun `a hallucinated package resolves to nothing rather than to something`() {
        val r = AppMatcher.resolve("com.instagram.android", installed)
        assertTrue("a package that is not installed must not match", r is Resolution.None)
    }

    @Test
    fun `an exact label never loses to a substring of a longer one`() {
        // "Google" is a substring of nothing else here, but "YouTube" is a prefix of "YouTube
        // Music" — an exact hit must still end the search rather than collecting both.
        assertEquals("com.google.android.googlequicksearchbox", pkgFor("Google"))
        assertEquals("com.google.android.youtube", pkgFor("YouTube"))
    }

    @Test
    fun `a genuinely ambiguous name is asked about, not guessed`() {
        val r = AppMatcher.resolve("file", installed)
        assertTrue("'file' matches both Files and File Manager, got $r", r is Resolution.Ambiguous)
        val labels = (r as Resolution.Ambiguous).candidates.map { it.label }
        assertTrue(labels.containsAll(listOf("Files", "File Manager")))
        assertEquals("shortest label first, so the likeliest is named first", "Files", labels.first())
    }

    @Test
    fun `a two-letter query does not substring-match half the phone`() {
        val r = AppMatcher.resolve("me", installed)
        assertTrue("a 2-char query must not resolve to an app, got $r", r is Resolution.None)
    }

    @Test
    fun `an empty or noise-only name resolves to nothing`() {
        assertTrue(AppMatcher.resolve("", installed) is Resolution.None)
        assertTrue(AppMatcher.resolve("   ", installed) is Resolution.None)
    }

    @Test
    fun `a miss carries near-misses so the reply can offer them`() {
        val r = AppMatcher.resolve("telegran", installed)
        assertTrue(r is Resolution.None)
        assertTrue(
            "expected Telegram offered as a near miss",
            (r as Resolution.None).near.any { it.label == "Telegram" }
        )
    }

    @Test
    fun `an empty index matches nothing rather than throwing`() {
        assertTrue(AppMatcher.resolve("Spotify", emptyList()) is Resolution.None)
    }
}
