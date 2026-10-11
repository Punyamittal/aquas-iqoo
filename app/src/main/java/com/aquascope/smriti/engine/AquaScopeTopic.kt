package com.aquascope.smriti.engine

/**
 * Ask covers AquaScope only: scanned places, dampness, leaks, scans and teaching.
 * Screen captures, apps and games are outside it, in questions and in model replies.
 */
object AquaScopeTopic {

    private val SCREEN_WORDS = Regex(
        """(?i)\b(screen|screens|screenshot|screenshots|screenmind|ocr|whatsapp|instagram|youtube|game|games|gaming|""" +
            """what app|which app|app opened|apps opened)\b|स्क्रीन|गेम|व्हाट्सएप"""
    )

    fun isScreenTopic(text: String): Boolean = SCREEN_WORDS.containsMatchIn(text)

    fun offTopicReply(language: String): String = when (language.lowercase()) {
        "hi" -> "मैं सिर्फ़ AquaScope स्कैन के बारे में जवाब देता हूँ: आपकी जगहें, वे गीली हैं या सूखी, और संभावित लीक। मैं आपकी स्क्रीन या दूसरे ऐप नहीं देखता।"
        "hinglish" -> "Main sirf AquaScope scans ke baare mein jawaab deta hoon: aapki jagahein, woh geeli hain ya sookhi, aur possible leaks. Main aapki screen ya doosre apps nahi dekhta."
        else -> "I only answer about your AquaScope scans: your places, whether they sound damp or dry, and possible leaks. I don't look at your screen or other apps."
    }
}
