package com.aquascope.smriti.skills

import java.util.Locale

/**
 * Picks the best enabled skill for a user question (Edge Gallery style: name + description).
 */
object SkillMatcher {

    fun match(question: String, skills: List<AgentSkill>): AgentSkill? {
        val q = question.lowercase(Locale.US).trim()
        if (q.isEmpty() || skills.isEmpty()) return null

        // Explicit kitchen adventure trigger from Gallery skill description.
        if (q.contains("kitchen adventure") || q.contains("start kitchen adventure")) {
            skills.firstOrNull { it.id == "kitchen-adventure" }?.let { return it }
        }

        val scored = skills.map { skill ->
            skill to score(q, skill)
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        return scored.firstOrNull()?.first
    }

    private fun score(q: String, skill: AgentSkill): Int {
        var s = 0
        val hay = (skill.name + " " + skill.description).lowercase(Locale.US)
        val tokens = hay.split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.distinct()
        tokens.forEach { t ->
            if (q.contains(t)) s += 2
        }
        when (skill.id) {
            "leak-explainer" -> if (hasAny(q, "leak", "drip", "pipe", "water sound", "plumbing")) s += 8
            "home-memory-brief" -> if (hasAny(q, "brief", "status", "what's going", "what is going", "home memory", "summar")) s += 6
            "query-wikipedia" -> {
                // "what is on my screen" / "what game" must not hit Wikipedia.
                val screenish = hasAny(
                    q, "screen", "clip", "recording", "game", "app opened", "on my phone", "ocr"
                )
                if (!screenish && hasAny(q, "who is", "what is", "wikipedia", "tell me about", "history of")) {
                    s += 5
                }
            }
            "calculate-hash" -> if (hasAny(q, "hash", "sha256", "sha-256", "checksum")) s += 10
            "send-email" -> if (hasAny(q, "email", "send mail", "compose mail", "e-mail")) s += 10
            "kitchen-adventure" -> if (hasAny(q, "adventure", "dungeon", "quest")) s += 4
        }
        return s
    }

    private fun hasAny(q: String, vararg needles: String): Boolean =
        needles.any { q.contains(it) }
}
