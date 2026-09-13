package dev.anilbeesetti.nextplayer.core.common.extensions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleSidecarDiscoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun matchesExactAndLanguageTaggedBasenames() {
        assertTrue(matchesSubtitleBasename("movie", "movie"))
        assertTrue(matchesSubtitleBasename("movie", "Movie"))
        assertTrue(matchesSubtitleBasename("movie", "movie.en"))
        assertTrue(matchesSubtitleBasename("movie", "movie.en.forced"))
        assertFalse(matchesSubtitleBasename("movie", "movies"))
        assertFalse(matchesSubtitleBasename("movie", "movieextra"))
        assertFalse(matchesSubtitleBasename("movie", "other"))
    }

    @Test
    fun findsExactAndTaggedSidecarsOrderedWithExactFirst() {
        val dir = tempFolder.newFolder("clips")
        val video = File(dir, "movie.mkv").also { it.writeText("v") }
        File(dir, "movie.en.vtt").also { it.writeText("en") }
        File(dir, "movie.vtt").also { it.writeText("exact") }
        File(dir, "movie.srt").also { it.writeText("srt") }
        File(dir, "movie.ass").also { it.writeText("ass") }
        File(dir, "other.vtt").also { it.writeText("other") }
        File(dir, "movies.vtt").also { it.writeText("prefix") }
        File(dir, "movie.mp4").also { it.writeText("not-sub") }

        val found = findSubtitleSidecars(video).map { it.name }

        assertEquals(
            listOf("movie.ass", "movie.srt", "movie.vtt", "movie.en.vtt"),
            found,
        )
    }

    @Test
    fun supportsCommonTextSubtitleExtensions() {
        val dir = tempFolder.newFolder("exts")
        val video = File(dir, "clip.mp4").also { it.writeText("v") }
        for (ext in listOf("srt", "ssa", "ass", "vtt", "ttml")) {
            File(dir, "clip.$ext").writeText(ext)
        }
        // .sub is not in the supported text-sidecar list
        File(dir, "clip.sub").writeText("sub")

        val found = findSubtitleSidecars(video).map { it.extension.lowercase() }.toSet()

        assertEquals(setOf("srt", "ssa", "ass", "vtt", "ttml"), found)
        assertFalse(File(dir, "clip.sub").isSubtitle())
        assertTrue(File(dir, "clip.vtt").isSubtitle())
    }

    @Test
    fun returnsEmptyWhenParentMissingOrNoMatches() {
        val orphan = File(tempFolder.root, "alone.mkv")
        orphan.writeText("v")
        // no parent listing with sidecars
        assertEquals(emptyList<File>(), findSubtitleSidecars(orphan))

        val dir = tempFolder.newFolder("empty")
        val video = File(dir, "video.mkv").also { it.writeText("v") }
        assertEquals(emptyList<File>(), findSubtitleSidecars(video))
    }
}
