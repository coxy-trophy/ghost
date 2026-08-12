package com.coxy.ghost.perception

import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.personality.PersonalityTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt assembly that both the typed and voice paths share. `buildUserPrompt` exists precisely
 * because those two paths had drifted apart, so the contract it enforces is worth asserting cheaply
 * on every build rather than only when a device is attached.
 *
 * `GhostPromptsTest` in androidTest covers the same substitution on-device; this is the fast copy
 * that fails in seconds when someone edits the base prompt.
 */
class PromptAssemblyTest {

    private val idle = ContextState()
    private val sensing = ContextState(timestampMs = 1L, activity = Activity.WALKING)

    @Test
    fun idleContextIsOmittedEntirely() {
        // The whole point: toPromptBlock() would happily emit four "unknown" fields.
        assertEquals("just the words", GhostPrompts.buildUserPrompt(idle, "", "just the words"))
    }

    @Test
    fun sensedContextRidesAboveTheUserText() {
        val prompt = GhostPrompts.buildUserPrompt(sensing, "", "what am I doing")
        val lines = prompt.lines()
        assertEquals(2, lines.size)
        assertTrue("context first", lines[0].startsWith("[CONTEXT "))
        assertEquals("user text last", "what am I doing", lines[1])
    }

    @Test
    fun memoryRidesBetweenContextAndUserText() {
        val prompt = GhostPrompts.buildUserPrompt(sensing, "[MEMORY user: \"hi\" (2d)]", "remember me?")
        val lines = prompt.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("[CONTEXT "))
        assertTrue(lines[1].startsWith("[MEMORY "))
        assertEquals("remember me?", lines[2])
    }

    @Test
    fun memoryWithoutContextStillRides() {
        val prompt = GhostPrompts.buildUserPrompt(idle, "[MEMORY user: \"hi\" (2d)]", "remember me?")
        assertEquals(listOf("[MEMORY user: \"hi\" (2d)]", "remember me?"), prompt.lines())
    }

    @Test
    fun systemPromptLeavesNoTemplatePlaceholderBehind() {
        val prompt = GhostPrompts.buildSystemPrompt(
            PersonalityTranslator.profileToPromptBlock(PersonalityTranslator.baseline())
        )
        assertFalse("a raw {{...}} placeholder shipped to the model", prompt.contains("{{"))
        assertFalse(prompt.contains("}}"))
    }

    @Test
    fun systemPromptNamesEveryActionGhostHas() {
        val prompt = GhostPrompts.buildSystemPrompt("[PERSONALITY test]")
        for (id in ActionCatalog.ids()) {
            assertTrue("action '$id' never reaches the model", prompt.contains(id))
        }
    }

    @Test
    fun tier3ActionsAreMarkedAsNeedingConfirmation() {
        val list = ActionCatalog.promptList()
        assertTrue(list.contains("uninstall_app(package) [only after he says yes]"))
        assertTrue(list.contains("send_sms(number,message) [only after he says yes]"))
        assertFalse("reversible actions must not be marked", list.contains("toggle_torch(state) [only after"))
    }
}
