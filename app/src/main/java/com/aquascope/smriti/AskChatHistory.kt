package com.aquascope.smriti

import android.content.Context
import com.aquascope.smriti.engine.AskChart
import org.json.JSONArray
import org.json.JSONObject

/** One question and its answer on the Ask screen. */
data class ChatTurn(
    val question: String,
    val answer: String,
    val evidenceState: String,
    val evidenceEventId: String? = null,
    val askedAt: Long = System.currentTimeMillis(),
    val conversationId: String = "c$askedAt",
    val chart: AskChart? = null
)

/** A saved Ask conversation, titled by its first question. */
data class ChatConversation(
    val id: String,
    val title: String,
    val startedAt: Long,
    val lastAt: Long,
    val turnCount: Int
)

/** Ask conversations saved on the phone, so they survive leaving the screen. */
class AskChatHistory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun all(): List<ChatTurn> = decode(prefs.getString(KEY_TURNS, null))

    private fun save(turns: List<ChatTurn>) {
        prefs.edit().putString(KEY_TURNS, encode(turns.takeLast(MAX_TURNS))).apply()
    }

    /** The conversation new questions go into; a fresh one after a long gap, like opening a new chat. */
    fun currentConversationId(now: Long = System.currentTimeMillis()): String {
        val saved = prefs.getString(KEY_CURRENT, null)
        val turns = all()
        val id = saved ?: turns.lastOrNull()?.conversationId
        val lastAt = turns.lastOrNull { it.conversationId == id }?.askedAt
        if (id != null && (lastAt == null || now - lastAt < NEW_CHAT_AFTER_MS)) {
            if (saved == null) prefs.edit().putString(KEY_CURRENT, id).apply()
            return id
        }
        return startNewConversation(now)
    }

    fun startNewConversation(now: Long = System.currentTimeMillis()): String {
        val id = "c$now"
        prefs.edit().putString(KEY_CURRENT, id).apply()
        return id
    }

    fun openConversation(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    fun turnsOf(conversationId: String): List<ChatTurn> = all().filter { it.conversationId == conversationId }

    fun conversations(): List<ChatConversation> = summarize(all())

    fun append(turn: ChatTurn) = save(all() + turn)

    fun deleteConversation(id: String) {
        save(all().filterNot { it.conversationId == id })
        if (prefs.getString(KEY_CURRENT, null) == id) prefs.edit().remove(KEY_CURRENT).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TURNS).remove(KEY_CURRENT).apply()
    }

    companion object {
        private const val PREFS = "ask_chat_history"
        private const val KEY_TURNS = "turns"
        private const val KEY_CURRENT = "current_conversation"
        const val MAX_TURNS = 500
        const val NEW_CHAT_AFTER_MS = 6 * 60 * 60 * 1000L

        /** Newest conversation first. */
        fun summarize(turns: List<ChatTurn>): List<ChatConversation> =
            turns.groupBy { it.conversationId }.map { (id, list) ->
                val sorted = list.sortedBy { it.askedAt }
                ChatConversation(
                    id = id,
                    title = sorted.first().question,
                    startedAt = sorted.first().askedAt,
                    lastAt = sorted.last().askedAt,
                    turnCount = sorted.size
                )
            }.sortedByDescending { it.lastAt }

        fun encode(turns: List<ChatTurn>): String = JSONArray().apply {
            turns.forEach { t ->
                put(
                    JSONObject()
                        .put("q", t.question)
                        .put("a", t.answer)
                        .put("state", t.evidenceState)
                        .put("event", t.evidenceEventId ?: JSONObject.NULL)
                        .put("at", t.askedAt)
                        .put("conv", t.conversationId)
                        .put("chart", t.chart?.encode() ?: JSONObject.NULL)
                )
            }
        }.toString()

        fun decode(json: String?): List<ChatTurn> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val at = o.optLong("at")
                ChatTurn(
                    question = o.optString("q"),
                    answer = o.optString("a"),
                    evidenceState = o.optString("state", "UNKNOWN"),
                    evidenceEventId = if (o.isNull("event")) null else o.optString("event"),
                    askedAt = at,
                    conversationId = o.optString("conv").ifBlank { "c$at" },
                    chart = if (o.isNull("chart")) null else AskChart.decode(o.optString("chart"))
                )
            }
        }
    }
}
