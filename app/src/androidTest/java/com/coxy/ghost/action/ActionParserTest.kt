package com.coxy.ghost.action

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 parser gate tests.
 *
 * Verifies that the model-reply parser extracts JSON action blocks, validates them against the
 * [ActionCatalog], strips the JSON from the user-visible reply, and reports errors for malformed or
 * disallowed actions.
 */
@RunWith(AndroidJUnit4::class)
class ActionParserTest {

    @Test
    fun extracts_single_action_from_code_fence() {
        val reply = """
            I'll turn on the torch.
            ```json
            {"action":"toggle_torch","state":"on"}
            ```
        """.trimIndent()

        val parsed = ActionParser.extract(reply)

        assertEquals("expected exactly one action", 1, parsed.actions.size)
        assertEquals("toggle_torch", parsed.actions[0].id)
        assertEquals(ActionTier.TIER_1_REVERSIBLE, parsed.actions[0].tier)
        assertEquals("on", parsed.actions[0].params["state"])
        assertTrue("no errors expected", parsed.errors.isEmpty())
        assertTrue("JSON block should be removed", "```" !in parsed.cleanedReply)
    }

    @Test
    fun extracts_inline_json_object() {
        val reply = "Opening Spotify now. {\"action\":\"launch_app\",\"package\":\"com.spotify.music\"}"

        val parsed = ActionParser.extract(reply)

        assertEquals(1, parsed.actions.size)
        assertEquals("launch_app", parsed.actions[0].id)
        assertEquals("com.spotify.music", parsed.actions[0].params["package"])
        assertTrue("inline JSON should be removed", "{" !in parsed.cleanedReply)
    }

    @Test
    fun extracts_array_of_actions() {
        val reply = """
            ```json
            [
              {"action":"get_battery"},
              {"action":"set_volume","value":"50"}
            ]
            ```
        """.trimIndent()

        val parsed = ActionParser.extract(reply)

        assertEquals(2, parsed.actions.size)
        assertEquals("get_battery", parsed.actions[0].id)
        assertEquals("set_volume", parsed.actions[1].id)
        assertEquals("50", parsed.actions[1].params["value"])
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun rejects_unknown_action() {
        val parsed = ActionParser.extract("{\"action\":\"factory_reset\"}")

        assertTrue("unknown action should not be parsed", parsed.actions.isEmpty())
        assertTrue("error should mention unknown action", parsed.errors.any { it.contains("factory_reset") })
    }

    @Test
    fun reports_missing_required_param() {
        val parsed = ActionParser.extract("{\"action\":\"toggle_torch\"}")

        assertTrue("incomplete action should be rejected", parsed.actions.isEmpty())
        assertTrue("error should mention missing state", parsed.errors.any { it.contains("state") })
    }

    @Test
    fun preserves_reply_text_without_json() {
        val parsed = ActionParser.extract("Just a normal sentence with no action.")

        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.errors.isEmpty())
        assertEquals("Just a normal sentence with no action.", parsed.cleanedReply)
    }
}
