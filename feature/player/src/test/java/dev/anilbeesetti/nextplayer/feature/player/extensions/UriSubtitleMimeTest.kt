package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.net.Uri
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UriSubtitleMimeTest {

    @Test
    fun getSubtitleMime_detectsVttCaseInsensitive() {
        assertEquals(MimeTypes.TEXT_VTT, Uri.parse("file:///sdcard/movie.vtt").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_VTT, Uri.parse("file:///sdcard/movie.VTT").getSubtitleMime())
        assertEquals(
            MimeTypes.TEXT_VTT,
            Uri.parse("content://com.android.providers.media.documents/document/primary%3ADownload%2Fmovie.vtt")
                .getSubtitleMime(),
        )
    }

    @Test
    fun getSubtitleMime_defaultsUnknownToSubrip() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            Uri.parse("content://downloads/my_downloads/42").getSubtitleMime(),
        )
    }
}
