package com.aquascope.smriti.engine

import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRecord
import com.aquascope.data.SerializableFeatures
import com.aquascope.smriti.llm.GroundedPromptBuilder
import com.aquascope.smriti.llm.SmritiAnswerComposer
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.SmritiAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanAnswererLanguageTest {

    private fun features(rnd: Random, damp: Boolean) = SerializableFeatures(
        6200 + rnd.nextDouble(-300.0, 300.0),
        1.5 + rnd.nextDouble(-0.1, 0.1),
        (if (damp) 5400.0 else 4800.0) + rnd.nextDouble(-100.0, 100.0),
        1850.0 + rnd.nextDouble(-50.0, 50.0),
        (if (damp) 0.73 else 0.81) + rnd.nextDouble(-0.02, 0.02)
    )

    private val places = listOf(
        Random(2).let { rnd ->
            ScanLocation(
                label = "tabl3",
                baselineFeatures = MutableList(8) { features(rnd, false) },
                moistFeatures = MutableList(8) { features(rnd, false) },
                scanHistory = mutableListOf(ScanRecord(3_000, features(rnd, true), 82.0))
            )
        }
    )

    private fun hasDevanagari(s: String) = s.any { it in '\u0900'..'\u097F' }

    @Test
    fun `hindi question gets a hindi scan answer`() {
        val q = "क्या tabl3 गीला है?"
        val lang = GroundedPromptBuilder.detectLanguage(q)
        assertEquals("hi", lang)
        val a = ScanAnswerer.answer(q, places, lang) { "t$it" }!!
        assertTrue(a, hasDevanagari(a))
        assertTrue(a, a.contains("भरोसा नहीं"))
    }

    @Test
    fun `hinglish question gets a hinglish scan answer`() {
        val q = "tabl3 geela hai kya?"
        val lang = GroundedPromptBuilder.detectLanguage(q)
        assertEquals("hinglish", lang)
        val a = ScanAnswerer.answer(q, places, lang) { "t$it" }!!
        assertFalse(a, hasDevanagari(a))
        assertTrue(a, a.contains("bharosa nahi"))
    }

    @Test
    fun `hindi words for scan topics are recognised`() {
        assertNotNull(ScanAnswerer.answer("पिछला स्कैन कहाँ हुआ?", places, "hi") { "t$it" })
        assertNotNull(ScanAnswerer.answer("konsi jagah pe nami hai?", places, "hinglish") { "t$it" })
    }

    @Test
    fun `selected language overrides the question language`() {
        assertEquals("hi", GroundedPromptBuilder.detectLanguage("which places are damp?", "hi"))
    }

    @Test
    fun `localized fallback keeps the original details and skips answers already localized`() {
        val english = SmritiAnswer(
            text = "Observed today: 1 Oct at kitchen",
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = emptyList(),
            evidence = null
        )
        val hi = SmritiAnswerComposer.localizeFallback(english, "hi")
        assertTrue(hi.text, hasDevanagari(hi.text))
        assertTrue(hi.text, hi.text.contains("1 Oct at kitchen"))
        assertFalse(hi.text, hi.text.contains("0"))

        val already = english.copy(text = "पहले से हिंदी", language = "hi")
        assertEquals("पहले से हिंदी", SmritiAnswerComposer.localizeFallback(already, "hi").text)
        assertEquals(english, SmritiAnswerComposer.localizeFallback(english, "en"))
    }
}
