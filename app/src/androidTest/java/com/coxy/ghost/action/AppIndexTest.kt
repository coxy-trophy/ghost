package com.coxy.ghost.action

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of app-launching that [AppMatcherTest] cannot prove.
 *
 * The matcher is pure and tested against a fixture; what a fixture cannot test is whether the index
 * is *given anything to match against*. Package visibility filtering on targetSdk 30+ is silent —
 * without the manifest `<queries>` element `queryIntentActivities` returns a near-empty list and
 * nothing throws, so the failure looks like "she can't find any app" rather than like a permission
 * problem. That is precisely the kind of fault this project keeps finding only on the device.
 */
@RunWith(AndroidJUnit4::class)
class AppIndexTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AppIndex.invalidate()
    }

    @Test
    fun theIndexSeesTheDeviceRatherThanJustItself() {
        val apps = AppIndex.entries(context)
        // The bar is deliberately low and still catches the visibility failure: filtered, this
        // comes back holding GHOST alone. The RMX3760 had 54 launchable apps when this was written.
        assertTrue("index holds ${apps.size} apps — visibility is being filtered", apps.size > 10)
        assertTrue(
            "index should not be GHOST alone",
            apps.any { it.pkg != "com.coxy.ghost" }
        )
    }

    @Test
    fun everyEntryCarriesALabelAndALaunchablePackage() {
        val pm = context.packageManager
        val apps = AppIndex.entries(context)
        for (app in apps) {
            assertTrue("blank label for ${app.pkg}", app.label.isNotBlank())
            assertTrue(
                "${app.pkg} was indexed but has no launch intent",
                pm.getLaunchIntentForPackage(app.pkg) != null
            )
        }
    }

    @Test
    fun ghostCanFindHerselfByName() {
        // The one app guaranteed installed wherever this test runs, so it is the only name that can
        // be asserted on without pinning the test to one particular phone.
        val r = AppIndex.resolve(context, "GHOST")
        assertTrue("resolving GHOST by label gave $r", r is AppMatcher.Resolution.Match)
        assertTrue((r as AppMatcher.Resolution.Match).entry.pkg == "com.coxy.ghost")
    }

    @Test
    fun anAppThatIsNotInstalledResolvesToNothing() {
        val r = AppIndex.resolve(context, "com.example.definitely.not.installed")
        assertTrue("must not match anything, got $r", r is AppMatcher.Resolution.None)
    }

    @Test
    fun everyIndexedAppCanBeFoundByItsOwnLabel() {
        // Round-trip: whatever the device is carrying, its labels are names the matcher answers to.
        // Ambiguity counts as found — two apps sharing a label prefix is the device's doing, not a
        // matcher failure — but a label resolving to *nothing* would mean the two halves disagree.
        val apps = AppIndex.entries(context)
        val lost = apps.filter { app ->
            AppMatcher.resolve(app.label, apps) is AppMatcher.Resolution.None
        }
        assertTrue("labels that match no app: ${lost.map { it.label }}", lost.isEmpty())
    }
}
