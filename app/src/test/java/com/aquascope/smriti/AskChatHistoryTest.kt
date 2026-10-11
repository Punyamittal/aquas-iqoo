package com.aquascope.smriti

import com.aquascope.smriti.engine.AskChart
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskChatHistoryTest {

    @Test
    fun turnsSurviveSaveAndLoad() {
        val turns = listOf(
            ChatTurn("Is table2 damp?", "Can't tell yet.", "UNKNOWN", null, 1L, "c1"),
            ChatTurn("क्या wework10 गीली है?", "हाँ, 95% नमी है।", "OBSERVED", "evt-7", 2L, "c1"),
            ChatTurn("chart please", "Here.", "OBSERVED", null, 3L, "c1", AskChart(AskChart.Kind.TREND, listOf("loc-1")))
        )
        assertEquals(turns, AskChatHistory.decode(AskChatHistory.encode(turns)))
    }

    @Test
    fun turnsSavedBeforeConversationsGetTheirOwnChat() {
        val old = JSONArray().put(
            JSONObject().put("q", "Which places are damp?").put("a", "weworks6").put("state", "OBSERVED").put("at", 42L)
        ).toString()
        assertEquals("c42", AskChatHistory.decode(old).single().conversationId)
    }

    @Test
    fun conversationsAreNewestFirstAndTitledByFirstQuestion() {
        val chats = AskChatHistory.summarize(
            listOf(
                ChatTurn("first", "a", "UNKNOWN", null, 10L, "old"),
                ChatTurn("second", "a", "UNKNOWN", null, 20L, "old"),
                ChatTurn("hello", "a", "UNKNOWN", null, 30L, "new")
            )
        )
        assertEquals(listOf("new", "old"), chats.map { it.id })
        assertEquals("first", chats[1].title)
        assertEquals(2, chats[1].turnCount)
    }

    @Test
    fun badOrMissingDataLoadsAsEmpty() {
        assertTrue(AskChatHistory.decode(null).isEmpty())
        assertTrue(AskChatHistory.decode("not json").isEmpty())
    }
}
