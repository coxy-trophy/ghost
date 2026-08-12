package com.coxy.ghost.perception

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.personality.PersonalityTranslator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression gate for the system prompt builder. The personality block and the action catalog are
 * substituted into the base role by placeholder; until this test existed they were shipped as raw
 * "{{PERSONALITY_BLOCK}}" / "{{ACTION_LIST}}" text, so GHOST was never told which actions it has.
 */
@RunWith(AndroidJUnit4::class)
class GhostPromptsTest {

    private fun prompt(): String =
        GhostPrompts.buildSystemPrompt(
            PersonalityTranslator.profileToPromptBlock(PersonalityTranslator.baseline())
        )

    @Test
    fun buildSystemPrompt_leavesNoUnsubstitutedPlaceholder() {
        val p = prompt()
        assertFalse("system prompt still contains a raw {{...}} placeholder", p.contains("{{"))
        assertFalse("system prompt still contains a raw placeholder terminator", p.contains("}}"))
    }

    @Test
    fun buildSystemPrompt_listsEveryCatalogAction() {
        val p = prompt()
        for (id in ActionCatalog.ids()) {
            assertTrue("action '$id' is missing from the system prompt", p.contains(id))
        }
    }

    @Test
    fun promptList_marksTier3AsNeedingConfirmation() {
        val list = ActionCatalog.promptList()
        assertTrue(list.contains("uninstall_app(package) [only after he says yes]"))
        assertTrue(list.contains("send_sms(number,message) [only after he says yes]"))
        // Tier 0-2 must not carry the confirmation marker.
        assertFalse(list.contains("toggle_torch(state) [only after"))
    }

    @Test
    fun buildSystemPrompt_keepsPersonalityBlockAndHardFloors() {
        val block = PersonalityTranslator.profileToPromptBlock(PersonalityTranslator.baseline())
        val p = prompt()
        assertTrue("personality block missing", p.contains(block))
        assertTrue("hard floors missing", p.contains(PersonalityTranslator.hardFloors()))
    }
}
