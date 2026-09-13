package dev.anilbeesetti.nextplayer.core.common.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UriDisplayNameTest {

    @Test
    fun decodeUriDisplayNameReplacesPercent20() {
        assertEquals("My Movie.en.vtt", decodeUriDisplayName("My%20Movie.en.vtt"))
        assertEquals("My Movie.en.vtt", decodeUriDisplayName("path/to/My%20Movie.en.vtt"))
        assertEquals("plain.srt", decodeUriDisplayName("plain.srt"))
    }

    @Test
    fun extractLanguageFromSidecarFilename() {
        assertEquals("en", extractSubtitleLanguageFromFilename("movie.en.vtt"))
        assertEquals("eng", extractSubtitleLanguageFromFilename("movie.eng.srt"))
        assertEquals("en", extractSubtitleLanguageFromFilename("My%20Movie.en.forced.vtt"))
        assertNull(extractSubtitleLanguageFromFilename("movie.srt"))
        assertNull(extractSubtitleLanguageFromFilename("movie"))
    }
}
