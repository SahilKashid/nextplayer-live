package dev.anilbeesetti.nextplayer.feature.player.utils

import android.app.Application
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaybackFailureLogTest {

    @After
    fun tearDown() {
        PlaybackFailureLogStore.clearForTest()
    }

    @Test
    fun reportIncludesHeaderUriAndExceptionFields() {
        val cause = IOException("unexpected end of stream")
        val error = PlaybackException(
            "Source error",
            cause,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        val report = PlaybackFailureLog.build(
            error = error,
            mediaUri = "file:///sdcard/Download/movie.mkv.part".toUri(),
            mediaId = "file:///sdcard/Download/movie.mkv.part",
            appInfo = PlaybackFailureLog.AppInfo(
                packageName = "dev.sahilkashid.nextplayer",
                versionName = "0.17.5",
                versionCode = 72,
            ),
            timestamp = "2026-09-11T21:30:00.000Z",
        )

        assertTrue(report, report.startsWith(PlaybackFailureLog.HEADER))
        assertTrue(report, report.contains("timestamp: 2026-09-11T21:30:00.000Z"))
        assertTrue(report, report.contains("package: dev.sahilkashid.nextplayer"))
        assertTrue(report, report.contains("version: 0.17.5 (72)"))
        assertTrue(report, report.contains("mediaId: file:///sdcard/Download/movie.mkv.part"))
        assertTrue(report, report.contains("scheme: file"))
        assertTrue(report, report.contains("PlaybackException.errorCode: ${PlaybackException.ERROR_CODE_IO_UNSPECIFIED}"))
        assertTrue(report, report.contains("PlaybackException.errorCodeName: "))
        assertTrue(report, report.contains("PlaybackException.message: Source error"))
        assertTrue(report, report.contains("java.io.IOException: unexpected end of stream"))
        assertTrue(report, report.contains("Stack traces:"))
    }

    @Test
    fun reportIncludesExoPlaybackExceptionType() {
        val error = ExoPlaybackException.createForSource(
            IOException("parser error"),
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
        val report = PlaybackFailureLog.build(
            error = error,
            mediaUri = "content://media/external/video/media/1".toUri(),
            mediaId = "content://media/external/video/media/1",
            appInfo = PlaybackFailureLog.AppInfo("pkg", "1.0", 1),
        )
        assertTrue(report, report.contains("ExoPlaybackException.type: SOURCE"))
        assertTrue(report, report.contains("scheme: content"))
    }

    @Test
    fun reportIncludesLocalFileDiagnostics() {
        val file = File.createTempFile("movie", ".mkv.part")
        try {
            file.writeBytes(ByteArray(128) { 1 })
            val context = RuntimeEnvironment.getApplication()
            val error = PlaybackException(
                "Can't play",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
            val report = PlaybackFailureLog.build(
                error = error,
                mediaUri = file.toUri(),
                mediaId = file.toURI().toString(),
                context = context,
            )
            assertTrue(report, report.contains("Local file diagnostics:"))
            assertTrue(report, report.contains("path: ${file.absolutePath}"))
            assertTrue(report, report.contains("exists: true"))
            assertTrue(report, report.contains("declaredLength: 128"))
            assertTrue(report, report.contains("readableEnd: "))
            assertTrue(report, report.contains("partialNameHeuristic: true"))
            assertTrue(report, report.contains("downloadPathHeuristic: "))
            assertTrue(report, report.contains("zeroTailReadableEnd: "))
            assertTrue(report, report.contains("isLikelyGrowing: "))
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncateCapsTotalLength() {
        val long = "x".repeat(20_000)
        val truncated = PlaybackFailureLog.truncate(long, maxChars = 100)
        assertEquals(100, truncated.length)
        assertTrue(truncated.endsWith("...[truncated]"))
    }

    @Test
    fun storeKeepsLatestAndWritesFile() {
        val context = RuntimeEnvironment.getApplication()
        PlaybackFailureLogStore.save(context, "dump-one")
        assertEquals("dump-one", PlaybackFailureLogStore.latest())
        val file = File(context.filesDir, PlaybackFailureLogStore.FILE_NAME)
        assertTrue(file.exists())
        assertEquals("dump-one", file.readText())
        PlaybackFailureLogStore.save(context, "dump-two")
        assertEquals("dump-two", PlaybackFailureLogStore.latest())
        assertFalse(PlaybackFailureLogStore.latest() == "dump-one")
    }
}
