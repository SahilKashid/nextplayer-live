package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue

/**
 * Pure helpers for near-playback-first **expanding-window** demux planning.
 *
 * Seed a modest band around the playhead, then repeatedly expand earlier / later
 * in growing steps so the UI sees many small progressive updates instead of a
 * harsh Phase-A / Phase-B cliff.
 */
internal object ExpandingCueWindow {

    /** Prefer near-playback extract when the user is at least this far into the file. */
    const val NEAR_FIRST_THRESHOLD_MS = 15_000L

    /** Seek a few seconds before playback so the active cue is usually included. */
    const val NEAR_SEEK_LEAD_MS = 5_000L

    /** Initial half-band after the seek point for the seed read (~± playhead). */
    const val SEED_HALF_WINDOW_MS = 40_000L

    /** Stop the seed read once we have about this many cues (emit ASAP). */
    const val SEED_TARGET_CUES = 40

    /** First expand step on each side of the seed coverage. */
    const val INITIAL_EXPAND_STEP_MS = 45_000L

    /** Cap for growing expand steps. */
    const val MAX_EXPAND_STEP_MS = 180_000L

    /** Grow the expand step by this much after each earlier+later pair. */
    const val EXPAND_STEP_INCREMENT_MS = 30_000L

    data class Window(
        val startMs: Long,
        val endMs: Long,
    ) {
        init {
            require(endMs >= startMs) { "endMs ($endMs) < startMs ($startMs)" }
        }
    }

    data class Coverage(
        /** Earliest timeline position we have already scanned (ms). */
        val startMs: Long,
        /** Latest timeline position we have already scanned (ms). */
        val endMs: Long,
        val reachedStart: Boolean,
        val reachedEof: Boolean,
    ) {
        val isComplete: Boolean get() = reachedStart && reachedEof
    }

    /** Seek/read window for the initial near-playhead seed band. */
    fun seedWindow(playbackPositionMs: Long): Window {
        val center = playbackPositionMs.coerceAtLeast(0L)
        val startMs = (center - NEAR_SEEK_LEAD_MS).coerceAtLeast(0L)
        val endMs = center + SEED_HALF_WINDOW_MS
        return Window(startMs = startMs, endMs = endMs)
    }

    fun shouldUseExpandingWindow(playbackPositionMs: Long, seekable: Boolean): Boolean =
        seekable && playbackPositionMs >= NEAR_FIRST_THRESHOLD_MS

    /**
     * Next earlier band to fill, or null when coverage already reaches time 0.
     * [coveredStartMs] is the current earliest scanned position.
     */
    fun nextEarlierWindow(coveredStartMs: Long, stepMs: Long): Window? {
        if (coveredStartMs <= 0L) return null
        val step = stepMs.coerceAtLeast(1L)
        val startMs = (coveredStartMs - step).coerceAtLeast(0L)
        if (startMs >= coveredStartMs) return null
        return Window(startMs = startMs, endMs = coveredStartMs)
    }

    /**
     * Next later band to fill, or null when EOF was already reached.
     * [coveredEndMs] is the current latest scanned position.
     */
    fun nextLaterWindow(coveredEndMs: Long, stepMs: Long, reachedEof: Boolean): Window? {
        if (reachedEof) return null
        val step = stepMs.coerceAtLeast(1L)
        return Window(startMs = coveredEndMs, endMs = coveredEndMs + step)
    }

    fun growStep(stepMs: Long): Long =
        (stepMs + EXPAND_STEP_INCREMENT_MS).coerceAtMost(MAX_EXPAND_STEP_MS)

    fun cueIdentityKey(cue: TimedCue): String =
        "${cue.startMs}|${cue.endMs}|${cue.text}"

    /**
     * Append [incoming] into [into] using cue-identity dedupe. Never removes existing
     * entries (snapshots only grow). Returns how many cues were newly added.
     */
    fun mergeByIdentity(
        into: MutableList<TimedCue>,
        seen: MutableSet<String>,
        incoming: Iterable<TimedCue>,
    ): Int {
        var added = 0
        for (cue in incoming) {
            if (seen.add(cueIdentityKey(cue))) {
                into += cue
                added++
            }
        }
        return added
    }

    /**
     * Ordered plan of expand windows after a successful seed: alternate earlier then
     * later until start+EOF coverage, growing the step each full pair.
     *
     * Pure helper for unit tests — the extractor applies the same rules online while
     * demuxing (and stops a later window early on EOF).
     */
    fun planExpandWindows(
        seed: Window,
        seedReachedEof: Boolean,
        maxSteps: Int = 64,
    ): List<Window> {
        var coverage = Coverage(
            startMs = seed.startMs,
            endMs = seed.endMs,
            reachedStart = seed.startMs <= 0L,
            reachedEof = seedReachedEof,
        )
        var step = INITIAL_EXPAND_STEP_MS
        val out = ArrayList<Window>()
        var i = 0
        while (!coverage.isComplete && i < maxSteps) {
            val earlier = nextEarlierWindow(coverage.startMs, step)
            if (earlier != null) {
                out += earlier
                coverage = coverage.copy(
                    startMs = earlier.startMs,
                    reachedStart = earlier.startMs <= 0L,
                )
            } else {
                coverage = coverage.copy(reachedStart = true)
            }

            val later = nextLaterWindow(coverage.endMs, step, coverage.reachedEof)
            if (later != null) {
                out += later
                // Planner cannot observe EOF; assume the planned end is scanned.
                coverage = coverage.copy(endMs = later.endMs)
            }

            if (earlier == null && later == null) break
            step = growStep(step)
            i++
        }
        return out
    }
}
