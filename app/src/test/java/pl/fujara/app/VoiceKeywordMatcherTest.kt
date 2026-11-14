package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeywordMatcherTest {
    @Test
    fun `normalizes polish diacritics`() {
        assertEquals("odrzuc", VoiceKeywordMatcher.normalize("Odrzuć!"))
        assertEquals("zolw lodz", VoiceKeywordMatcher.normalize("Żółw Łódź"))
    }

    @Test
    fun `keyword matches with and without diacritics and inflection`() {
        assertTrue(VoiceKeywordMatcher.matches(listOf("odrzuc"), "odrzuć"))
        assertTrue(VoiceKeywordMatcher.matches(listOf("fujara odrzuć to"), "odrzuć"))
        assertTrue(VoiceKeywordMatcher.matches(listOf("odrzucić"), "odrzuć"))
        assertFalse(VoiceKeywordMatcher.matches(listOf("akceptuj"), "odrzuć"))
        assertFalse(VoiceKeywordMatcher.matches(listOf("nawiguj do klienta"), "odrzuć"))
    }

    @Test
    fun `confirmation and negation`() {
        assertTrue(VoiceKeywordMatcher.isConfirmation(listOf("odrzucam"), "odrzucam"))
        assertTrue(VoiceKeywordMatcher.isConfirmation(listOf("tak"), "odrzucam"))
        assertFalse(VoiceKeywordMatcher.isConfirmation(listOf("nie wiem"), "odrzucam"))
        assertTrue(VoiceKeywordMatcher.isNegation(listOf("nie")))
        assertTrue(VoiceKeywordMatcher.isNegation(listOf("anuluj to")))
        assertFalse(VoiceKeywordMatcher.isNegation(listOf("odrzucam")))
    }
}
