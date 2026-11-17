package pl.fujara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun `polish commands`() {
        assertEquals(VoiceCommand.COLLAPSE, VoiceCommandMatcher.parse(listOf("zwiń")))
        assertEquals(VoiceCommand.EXPAND, VoiceCommandMatcher.parse(listOf("Rozwiń")))
        assertEquals(VoiceCommand.EXPAND, VoiceCommandMatcher.parse(listOf("pokaż więcej")))
        assertEquals(VoiceCommand.ZOOM_IN, VoiceCommandMatcher.parse(listOf("przybliż")))
        assertEquals(VoiceCommand.ZOOM_IN, VoiceCommandMatcher.parse(listOf("przybliż mapę")))
        assertEquals(VoiceCommand.ZOOM_OUT, VoiceCommandMatcher.parse(listOf("oddal")))
        assertEquals(VoiceCommand.ZOOM_OUT, VoiceCommandMatcher.parse(listOf("zoom out")))
        assertEquals(VoiceCommand.COLLAPSE, VoiceCommandMatcher.parse(listOf("schowaj panel")))
    }

    @Test
    fun `reject keyword is not a command`() {
        assertNull(VoiceCommandMatcher.parse(listOf("odrzuć")))
        assertNull(VoiceCommandMatcher.parse(listOf("odrzucam")))
        assertNull(VoiceCommandMatcher.parse(listOf("")))
    }

    @Test
    fun `cyrillic commands survive normalization`() {
        assertEquals(VoiceCommand.COLLAPSE, VoiceCommandMatcher.parse(listOf("сверни")))
        assertEquals(VoiceCommand.ZOOM_IN, VoiceCommandMatcher.parse(listOf("приблизь")))
    }
}
