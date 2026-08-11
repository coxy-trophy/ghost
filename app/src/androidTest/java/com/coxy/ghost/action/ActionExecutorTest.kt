package com.coxy.ghost.action

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 executor gate tests.
 *
 * These run in the target app process and exercise the real [ActionExecutor] against the live
 * Android framework. Read-only Tier 0 actions are verified directly; Tier 3 actions are verified
 * to be deferred to user confirmation rather than auto-executed.
 */
@RunWith(AndroidJUnit4::class)
class ActionExecutorTest {

    private lateinit var context: Context
    private lateinit var executor: ActionExecutor

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        executor = ActionExecutor(context)
    }

    @Test
    fun tier0_read_battery_succeeds() = runBlocking {
        val actions = listOf(Action("get_battery", ActionTier.TIER_0_READ_ONLY))

        val batch = executor.runImmediate(actions)

        assertTrue("Tier 0 should execute immediately", batch.pending.isEmpty())
        assertEquals(1, batch.results.size)
        assertTrue("battery result should include a percentage", batch.results[0].success)
        assertTrue("battery result missing %", batch.results[0].message.contains("%"))
    }

    @Test
    fun tier3_action_is_pending_and_not_executed() = runBlocking {
        val actions = listOf(
            Action(
                "send_sms",
                ActionTier.TIER_3_DESTRUCTIVE,
                mapOf("number" to "+123", "message" to "test")
            )
        )

        val batch = executor.runImmediate(actions)

        assertTrue("Tier 3 should not auto-execute", batch.results.isEmpty())
        assertEquals(1, batch.pending.size)
        assertEquals("send_sms", batch.pending[0].id)
    }

    @Test
    fun tier1_toggle_torch_succeeds_and_can_be_turned_off() = runBlocking {
        val on = executor.runImmediate(
            listOf(Action("toggle_torch", ActionTier.TIER_1_REVERSIBLE, mapOf("state" to "on")))
        )
        assertTrue("torch-on should execute immediately", on.pending.isEmpty())
        assertEquals(1, on.results.size)
        assertTrue("torch-on should succeed", on.results[0].success)
        assertTrue("result should say torch on", on.results[0].message.contains("on"))

        // Restore LED state so the test leaves the device as it was.
        val off = executor.runImmediate(
            listOf(Action("toggle_torch", ActionTier.TIER_1_REVERSIBLE, mapOf("state" to "off")))
        )
        assertTrue("torch-off should succeed", off.results[0].success)
    }

    /**
     * Regression: SuExecutor's package-name regex rejected every valid package name, so this
     * Tier 2 action always came back "invalid package name" without ever reaching the shell.
     *
     * The target package deliberately does not exist, so `am force-stop` is a no-op. The assertion
     * is about validation, not about the shell succeeding — a device without root still passes.
     */
    @Test
    fun tier2_force_stop_accepts_a_valid_package_name() = runBlocking {
        val batch = executor.runImmediate(
            listOf(
                Action(
                    "force_stop_app",
                    ActionTier.TIER_2_DISRUPTIVE,
                    mapOf("package" to "com.coxy.ghost.nonexistent.testtarget")
                )
            )
        )

        assertTrue("Tier 2 should execute immediately", batch.pending.isEmpty())
        assertEquals(1, batch.results.size)
        assertFalse(
            "valid package name was rejected by the validator: ${batch.results[0].message}",
            batch.results[0].message.contains("invalid package name")
        )
    }

    @Test
    fun tier2_force_stop_rejects_shell_injection() = runBlocking {
        val batch = executor.runImmediate(
            listOf(
                Action(
                    "force_stop_app",
                    ActionTier.TIER_2_DISRUPTIVE,
                    mapOf("package" to "com.coxy.ghost; reboot")
                )
            )
        )

        assertEquals(1, batch.results.size)
        assertFalse("injection attempt must not succeed", batch.results[0].success)
        assertTrue(
            "should be rejected by the validator, got: ${batch.results[0].message}",
            batch.results[0].message.contains("invalid package name")
        )
    }

    @Test
    fun action_result_formatting() {
        val ok = ActionResult("launch_app", true, "launched com.spotify.music")
        assertTrue("ok result should be marked ok", ok.toReplyLine().contains("ACTION ok"))

        val fail = ActionResult("set_brightness", false, "permission denied")
        assertTrue("fail result should be marked fail", fail.toReplyLine().contains("ACTION fail"))
    }
}
