package com.aquascope.smriti.llm

import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.SmritiAnswer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmritiAnswerComposerTest {

    private val rule = SmritiAnswer(
        text = "I detected 2 acoustic deviation record(s). Status remains UNCONFIRMED / POSSIBLE — not CONFIRMED.",
        evidenceState = EvidenceState.POSSIBLE,
        relatedEvents = emptyList(),
        evidence = null
    )

    @Test
    fun `allows possible leak phrasing without confirmed language`() {
        assertTrue(
            SmritiAnswerComposer.passesGroundingCheck(
                "There is a leak under the sink, but it is not confirmed.",
                rule
            )
        )
    }

    @Test
    fun `rejects invented confirmed leak`() {
        assertFalse(
            SmritiAnswerComposer.passesGroundingCheck(
                "Yes, there is a confirmed leak in the kitchen.",
                rule
            )
        )
    }

    @Test
    fun `allows rephrase without new claims`() {
        assertTrue(
            SmritiAnswerComposer.passesGroundingCheck(
                "I found two unusual acoustic readings. This looks possible but is not a confirmed leak.",
                rule
            )
        )
    }

    @Test
    fun `allows a spoken chat-length rephrase of a short rule answer`() {
        val spoken = "I don't have a scan for that yet. Try a kitchen scan, or ask about today's activity."
        assertTrue(
            SmritiAnswerComposer.passesGroundingCheck(
                spoken,
                rule,
                com.aquascope.smriti.model.QueryIntent.GENERAL
            )
        )
    }

    @Test
    fun `prompt asks model to answer from APP_DATA without inventing`() {
        val prompt = GroundedPromptBuilder.buildAsk("Is it a leak?", rule, emptyList())
        assertTrue(prompt.contains("APP_DATA"))
        assertTrue(prompt.contains("Never invent") || prompt.contains("Do NOT invent"))
        assertTrue(prompt.contains(rule.text))
        assertTrue(prompt.contains("APP_HINT"))
        assertTrue(prompt.contains("AquaScope assistant"))
        assertTrue(prompt.contains("Never mention screens"))
        assertFalse(prompt.contains("SCREEN_OCR"))
    }
}
