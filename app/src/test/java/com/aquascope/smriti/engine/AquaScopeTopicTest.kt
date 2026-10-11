package com.aquascope.smriti.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AquaScopeTopicTest {

    @Test
    fun screenAndAppQuestionsAreOffTopic() {
        assertTrue(AquaScopeTopic.isScreenTopic("what was on my screen?"))
        assertTrue(AquaScopeTopic.isScreenTopic("what game did I open"))
        assertTrue(AquaScopeTopic.isScreenTopic("Check your WhatsApp messages"))
        assertTrue(AquaScopeTopic.isScreenTopic("मेरी स्क्रीन पर क्या था?"))
    }

    @Test
    fun scanQuestionsAndAnswersStayOnTopic() {
        assertFalse(AquaScopeTopic.isScreenTopic("Which places are damp?"))
        assertFalse(AquaScopeTopic.isScreenTopic("what should i do next"))
        assertFalse(AquaScopeTopic.isScreenTopic("Rescan wework10 and teach two more damp examples."))
        assertFalse(AquaScopeTopic.isScreenTopic("क्या रसोई की दीवार अब भी गीली है?"))
    }

    @Test
    fun offTopicReplyFollowsLanguage() {
        assertTrue(AquaScopeTopic.offTopicReply("hi").any { it in '\u0900'..'\u097F' })
        assertTrue(AquaScopeTopic.offTopicReply("hinglish").contains("sirf AquaScope"))
        assertTrue(AquaScopeTopic.offTopicReply("en").startsWith("I only answer"))
    }
}
