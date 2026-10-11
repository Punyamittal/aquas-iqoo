package com.aquascope.smriti.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerLanguageTest {

    private val english = "The places that sound damp are wework10 and weworks6. Rescan them to check."
    private val french = "Les endroits qui semblent humides sont wework10 et weworks6. Refaites un scan pour vérifier."
    private val spanish = "Los lugares que parecen húmedos son wework10 y weworks6. Vuelve a escanear para comprobar."
    private val german = "Die Stellen, die feucht klingen, sind wework10 und weworks6. Scanne sie noch einmal."
    private val hinglish = "wework10 aur weworks6 damp lagte hain. Inhe dobara scan kar ke check kijiye, abhi pakka nahi hai."

    @Test
    fun `detects common answer languages`() {
        assertEquals("en", AnswerLanguage.detect(english))
        assertEquals("fr", AnswerLanguage.detect(french))
        assertEquals("es", AnswerLanguage.detect(spanish))
        assertEquals("de", AnswerLanguage.detect(german))
        assertEquals("hinglish", AnswerLanguage.detect(hinglish))
        assertEquals("hi", AnswerLanguage.detect("wework10 और weworks6 गीले लगते हैं।"))
    }

    @Test
    fun `english reply does not pass for another selected language`() {
        assertFalse(AnswerLanguage.matches(english, "fr"))
        assertFalse(AnswerLanguage.matches(english, "hinglish"))
        assertFalse(AnswerLanguage.matches(english, "hi"))
        assertTrue(AnswerLanguage.matches(french, "fr"))
        assertTrue(AnswerLanguage.matches(english, "en"))
    }

    private class FakeLlm(private val reply: (String) -> String?) : LocalLlmEngine {
        val prompts = mutableListOf<String>()
        override val isReady = true
        override val modelLabel = "fake"
        override fun generate(prompt: String): String? {
            prompts += prompt
            return reply(prompt)
        }
        override fun close() = Unit
    }

    @Test
    fun `translation in the selected language is accepted`() {
        val llm = FakeLlm { french }
        assertEquals(french, AnswerLanguage.translate(llm, english, "fr"))
        assertTrue(llm.prompts.single().contains("into French"))
    }

    @Test
    fun `translation that stays english or changes numbers is refused`() {
        assertNull(AnswerLanguage.translate(FakeLlm { english }, english, "fr"))
        val changed = FakeLlm { "Les endroits humides sont à 87% et il faut vérifier les murs maintenant." }
        assertNull(AnswerLanguage.translate(changed, "wework10 scored 95%.", "fr"))
    }
}
