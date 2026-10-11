package com.aquascope.smriti.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class HindiTextRepairTest {

    private fun gpt2Mojibake(text: String): String {
        val map = HashMap<Int, Char>()
        var next = 256
        for (b in 0..255) {
            val printable = b in 0x21..0x7E || b in 0xA1..0xAC || b in 0xAE..0xFF
            map[b] = if (printable) b.toChar() else (next++).toChar()
        }
        return text.toByteArray(Charsets.UTF_8).joinToString("") { map[it.toInt() and 0xFF]!!.toString() }
    }

    @Test
    fun englishIsUntouched() {
        val s = "Kitchen wall is dry. Score 12%."
        assertEquals(s, HindiTextRepair.repair(s))
    }

    @Test
    fun undoesByteLevelMojibake() {
        assertEquals("दीवार", HindiTextRepair.fixMojibake(gpt2Mojibake("दीवार")))
        assertEquals("दीवार गीली है", BpeDecoder.decode(gpt2Mojibake("दीवार गीली है")))
    }

    @Test
    fun undoesLatin1Mojibake() {
        val broken = String("है".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("है", HindiTextRepair.fixMojibake(broken))
    }

    @Test
    fun joinsSignsSplitBySpaces() {
        assertEquals("यह गीली है।", HindiTextRepair.repair("यह ग ीली ह ै।"))
    }

    @Test
    fun joinsSplitConjunct() {
        assertEquals("क्षेत्र", HindiTextRepair.repair("क् षेत्र"))
    }

    @Test
    fun dropsOrphanAndRepeatedSigns() {
        assertEquals("नमी है", HindiTextRepair.repair("ानमीी हैे"))
    }

    @Test
    fun fixesCommonMisspellings() {
        assertEquals(
            "दीवार में नमी नहीं है।",
            HindiTextRepair.repair("दिवार मे नमि नही है।")
        )
    }

    @Test
    fun fixesDandaPunctuation() {
        assertEquals("table2 गीली है। स्कैन करें।", HindiTextRepair.repair("table2 गीली है | स्कैन करें."))
    }

    @Test
    fun keepsNumbersAndPlaceNames() {
        assertEquals("wework10 में 95% नमी है।", HindiTextRepair.repair("wework10 मे 95% नमी है."))
    }

    @Test
    fun removesStrayZeroWidthCharacters() {
        assertEquals("नमी है", HindiTextRepair.repair("\u200Bनमी \u200Dहै\uFEFF"))
    }

    @Test
    fun cleanerAppliesRepair() {
        assertEquals("दीवार गीली है।", AskAnswerCleaner.cleanModelOutput("दिवार गिली है."))
    }
}
