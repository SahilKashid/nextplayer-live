package dev.anilbeesetti.nextplayer.feature.player.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import dev.anilbeesetti.nextplayer.feature.player.utils.subtitle.SubtitleCueLoader
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AutoFollowResumeDelay = 3.seconds
private val FastHighlightTick = 50.milliseconds
private val IdleHighlightTick = 500.milliseconds
// Start centering the upcoming cue slightly before it becomes active so the
// slide finishes as bold/highlight lands (matches scroll animation length).
private val ScrollLeadMs = 380L
private val ScrollLeadHysteresisMs = 120L
private val DisableLeadAfterSeekMs = 500L
private val PartialCoalesceWindow = 48.milliseconds

@UnstableApi
@Composable
fun rememberLiveSubtitlesState(
    player: Player,
    subtitleDelayMs: Long = 0L,
    subtitleSpeed: Float = 1f,
): LiveSubtitlesState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(player) { LiveSubtitlesState(player, context, scope) }
    LaunchedEffect(player) { state.observe() }
    LaunchedEffect(subtitleDelayMs, subtitleSpeed) {
        state.subtitleDelayMs = subtitleDelayMs
        state.subtitleSpeed = subtitleSpeed
        // Delay/speed only affect the position fallback path — EVENT_CUES already
        // reflect nextlib's OffsetRenderer adjustment.
        state.updateCurrentCueIndexFromPlayer()
    }
    return state
}

@Stable
@UnstableApi
class LiveSubtitlesState(
    private val player: Player,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var cues: List<TimedCue> by mutableStateOf(emptyList())
        private set

    var currentCueIndex: Int by mutableIntStateOf(-1)
        private set

    /** Index the list should center on — may lead [currentCueIndex] for early scroll. */
    var scrollTargetIndex: Int by mutableIntStateOf(-1)
        private set

    /**
     * Stable identity of the bold/color highlighted cue (never the early-lead scroll
     * target). Survives remux index shifts without visual chatter.
     */
    var highlightedCueKey: String? by mutableStateOf(null)
        private set

    /**
     * Stable identity of the cue the list should center on. Scroll effects key on
     * this (not index) so a completed remux that only remaps indexes does not flicker.
     */
    var scrollTargetKey: String? by mutableStateOf(null)
        private set

    var isPanelVisible: Boolean by mutableStateOf(false)
        private set

    var isFollowing: Boolean by mutableStateOf(true)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var isUnsupportedTrack: Boolean by mutableStateOf(false)
        private set

    var subtitleDelayMs: Long = 0L

    var subtitleSpeed: Float by mutableFloatStateOf(1f)

    private var loadJob: Job? = null
    private var resumeFollowJob: Job? = null
    private var lastTrackSignature: String? = null
    /** Realtime millis until which scroll/highlight lead is disabled (after seek). */
    private var leadDisabledUntilElapsedMs: Long = 0L

    fun togglePanel() {
        isPanelVisible = !isPanelVisible
        if (isPanelVisible) {
            isFollowing = true
            resumeFollowJob?.cancel()
            updateCurrentCueIndexFromPlayer()
        }
    }

    fun updatePanelVisible(visible: Boolean) {
        isPanelVisible = visible
        if (visible) {
            isFollowing = true
            resumeFollowJob?.cancel()
            updateCurrentCueIndexFromPlayer()
        }
    }

    fun onUserScroll() {
        isFollowing = false
        resumeFollowJob?.cancel()
        resumeFollowJob = scope.launch {
            delay(AutoFollowResumeDelay)
            isFollowing = true
        }
    }

    fun jumpToCurrent() {
        resumeFollowJob?.cancel()
        isFollowing = true
    }

    fun seekToCue(cue: TimedCue) {
        leadDisabledUntilElapsedMs =
            android.os.SystemClock.elapsedRealtime() + DisableLeadAfterSeekMs
        player.seekTo(cue.startMs.coerceAtLeast(0L))
        jumpToCurrent()
        updateCurrentCueIndexFromPosition(cue.startMs.coerceAtLeast(0L))
    }

    suspend fun observe() {
        reloadIfNeeded(force = true)
        updateCurrentCueIndexFromPlayer()

        coroutineScope {
            launch {
                player.listen { events ->
                    if (events.containsAny(
                            Player.EVENT_TRACKS_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_TIMELINE_CHANGED,
                        )
                    ) {
                        reloadIfNeeded(force = false)
                    }
                    if (events.contains(Player.EVENT_CUES)) {
                        updateCurrentCueIndexFromCues()
                    }
                    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                        // Rewind/seek: drop lead briefly so highlight doesn't chatter
                        // between current and next around the lead threshold.
                        leadDisabledUntilElapsedMs =
                            android.os.SystemClock.elapsedRealtime() + DisableLeadAfterSeekMs
                        updateCurrentCueIndexFromPosition(player.currentPosition)
                    } else if (events.containsAny(
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED,
                        )
                    ) {
                        updateCurrentCueIndexFromPlayer()
                    }
                }
            }

            // Fast position fallback while the panel is visible so highlight stays
            // in lockstep even between EVENT_CUES (and when currentCues is empty).
            while (true) {
                delay(if (isPanelVisible) FastHighlightTick else IdleHighlightTick)
                if (!isPanelVisible) continue
                updateCurrentCueIndexFromPlayer()
            }
        }
    }

    /**
     * Prefer Media3 current cues (same source as the on-video overlay). Fall back to
     * a delay-adjusted position match when no cue is active.
     */
    fun updateCurrentCueIndexFromPlayer() {
        if (android.os.SystemClock.elapsedRealtime() < leadDisabledUntilElapsedMs) {
            updateCurrentCueIndexFromPosition(player.currentPosition)
            return
        }
        val active = player.currentCues.cues
        if (active.isNotEmpty() && matchCuesToIndex(active)) {
            updateScrollTarget()
            return
        }
        updateCurrentCueIndexFromPosition(player.currentPosition)
    }

    fun updateCurrentCueIndexFromCues() {
        // Right after rewind/seek, trust timeline position over cue-text matching
        // so duplicate lines don't make bold/color chatter.
        if (android.os.SystemClock.elapsedRealtime() < leadDisabledUntilElapsedMs) {
            updateCurrentCueIndexFromPosition(player.currentPosition)
            return
        }
        val active = player.currentCues.cues
        if (active.isNotEmpty() && matchCuesToIndex(active)) {
            updateScrollTarget()
            return
        }
        updateCurrentCueIndexFromPosition(player.currentPosition)
    }

    /**
     * Position-based fallback matching nextlib OffsetRenderer semantics:
     * `effectiveUs = positionUs * speed - delayMs * 1000`.
     * Do **not** use this path for EVENT_CUES text matches (delay already applied).
     */
    fun updateCurrentCueIndexFromPosition(positionMs: Long = player.currentPosition) {
        if (cues.isEmpty()) {
            commitCurrentCueIndex(-1)
            commitScrollTargetIndex(-1)
            return
        }
        val speed = subtitleSpeed.coerceIn(0.1f, 10f)
        val effective = (positionMs.toDouble() * speed - subtitleDelayMs.toDouble()).toLong()
        val index = cues.indexOfLast { cue ->
            effective >= cue.startMs && effective < cue.endMs
        }.takeIf { it >= 0 } ?: cues.indexOfLast { cue -> effective >= cue.startMs }
        commitCurrentCueIndex(index)
        updateScrollTarget(positionMs)
    }


    /**
     * Lead **scroll only** toward the next cue shortly before it becomes current.
     * Bold/color use [highlightedCueKey] from the true current cue and never lead.
     * Adaptive lead + hysteresis avoid scroll chatter after rewind.
     */
    fun updateScrollTarget(positionMs: Long = player.currentPosition) {
        val current = currentCueIndex
        if (current !in cues.indices) {
            commitScrollTargetIndex(current)
            return
        }
        val next = current + 1
        if (next !in cues.indices) {
            commitScrollTargetIndex(current)
            return
        }

        // After seek/rewind, stick to the true current cue until lead re-enables.
        if (android.os.SystemClock.elapsedRealtime() < leadDisabledUntilElapsedMs) {
            commitScrollTargetIndex(current)
            return
        }

        val speed = subtitleSpeed.coerceIn(0.1f, 10f)
        val effective = (positionMs.toDouble() * speed - subtitleDelayMs.toDouble()).toLong()
        val gapToNext = (cues[next].startMs - cues[current].startMs).coerceAtLeast(0L)
        val leadMs = when {
            gapToNext <= 280L -> 0L
            gapToNext <= 560L -> gapToNext / 3
            else -> ScrollLeadMs
        }
        if (leadMs <= 0L) {
            commitScrollTargetIndex(current)
            return
        }

        val untilNext = cues[next].startMs - effective
        val alreadyLeading = scrollTargetKey == cues.getOrNull(next)?.identityKey()
        val enterLead = untilNext in 0..leadMs
        // Leave lead only after we're clearly outside the window (+ hysteresis).
        val leaveLead = untilNext > leadMs + ScrollLeadHysteresisMs || untilNext < 0L
        val target = when {
            alreadyLeading && !leaveLead -> next
            !alreadyLeading && enterLead -> next
            else -> current
        }
        commitScrollTargetIndex(target)
    }

    /** @deprecated Use [updateCurrentCueIndexFromPlayer]. Kept for older call sites. */
    fun updateCurrentCueIndex(positionMs: Long = player.currentPosition) {
        updateCurrentCueIndexFromPosition(positionMs)
    }

    private fun matchCuesToIndex(activeCues: List<Cue>): Boolean {
        if (cues.isEmpty()) return false
        val activeTexts = activeCues.mapNotNull { cue ->
            cue.text?.toString()?.normalizeCueText()?.takeIf { it.isNotEmpty() }
        }
        if (activeTexts.isEmpty()) return false

        val joined = activeTexts.joinToString("\n")
        val candidates = ArrayList<Int>()
        for (i in cues.indices) {
            val normalized = cues[i].text.normalizeCueText()
            if (normalized == joined || activeTexts.any { it == normalized }) {
                candidates += i
            }
        }
        // Soft contains matching can oscillate across progressive partial lists.
        // Prefer exact normalized matches; only soft-match once loading is finished.
        if (candidates.isEmpty() && !isLoading) {
            for (i in cues.indices) {
                val normalized = cues[i].text.normalizeCueText()
                if (activeTexts.any { active ->
                        normalized.contains(active) || active.contains(normalized)
                    }
                ) {
                    candidates += i
                }
            }
        }
        if (candidates.isEmpty()) return false

        if (candidates.size == 1) {
            commitCurrentCueIndex(candidates[0])
            return true
        }

        // Disambiguate duplicate texts near the delay-adjusted position.
        val speed = subtitleSpeed.coerceIn(0.1f, 10f)
        val effective = (player.currentPosition.toDouble() * speed - subtitleDelayMs.toDouble()).toLong()
        commitCurrentCueIndex(
            candidates.minBy { idx ->
                val cue = cues[idx]
                abs((cue.startMs + cue.endMs) / 2L - effective)
            },
        )
        return true
    }

    private fun reloadIfNeeded(force: Boolean) {
        val signature = trackSignature()
        if (!force && signature == lastTrackSignature) return
        lastTrackSignature = signature
        loadJob?.cancel()
        loadJob = scope.launch {
            if (signature == "none") {
                cues = emptyList()
                isUnsupportedTrack = false
                isLoading = false
                currentCueIndex = -1
                scrollTargetIndex = -1
                highlightedCueKey = null
                scrollTargetKey = null
                return@launch
            }

            // Only spin when we have nothing to show yet. Cache hits return immediately
            // from memory/disk inside the loader so reopen / track reselect stays snappy.
            // Progressive demux partials also clear the spinner as soon as cues arrive.
            if (cues.isEmpty()) {
                isLoading = true
            }
            val loadGeneration = lastTrackSignature
            // Coalesce progressive Main updates so Compose is not flooded every partial.
            var pendingPartial: List<TimedCue>? = null
            var coalesceJob: Job? = null
            val result = SubtitleCueLoader.loadSelectedTrackCuesDetailed(
                context = context,
                player = player,
                onPartialCues = { partial ->
                    // Called from the IO demux thread — hop to Main without blocking demux.
                    scope.launch(Dispatchers.Main.immediate) {
                        if (lastTrackSignature != loadGeneration) return@launch
                        if (partial.isEmpty()) return@launch
                        // Prefer larger / newer snapshots so phase merges don't regress.
                        val latestPending = pendingPartial
                        if (cues.isNotEmpty() && partial.size < cues.size) return@launch
                        if (latestPending != null && partial.size < latestPending.size) return@launch
                        pendingPartial = partial
                        if (coalesceJob?.isActive == true) return@launch
                        coalesceJob = scope.launch(Dispatchers.Main.immediate) {
                            delay(PartialCoalesceWindow)
                            val toApply = pendingPartial ?: return@launch
                            pendingPartial = null
                            if (lastTrackSignature != loadGeneration) return@launch
                            if (toApply.isEmpty()) return@launch
                            if (cues.isNotEmpty() && toApply.size < cues.size) return@launch
                            applyCuesPreservingActiveIdentity(toApply)
                            isUnsupportedTrack = false
                        }
                    }
                },
            )
            withContext(Dispatchers.Main.immediate) {
                if (lastTrackSignature != loadGeneration) return@withContext
                coalesceJob?.cancel()
                pendingPartial = null
                applyCuesPreservingActiveIdentity(result.cues)
                isUnsupportedTrack = result.cues.isEmpty()
                isLoading = false
            }
        }
    }

    /**
     * Replace the cue list while keeping highlight on the same cue identity when
     * Phase-B prepends earlier cues (index shifts, identity unchanged). Only fall
     * back to player matching when that cue disappeared from the new list.
     */
    private fun applyCuesPreservingActiveIdentity(newCues: List<TimedCue>) {
        // Preserve by identity strings so remux completion does not change keys
        // (and therefore does not flash bold/color or restart scroll).
        val prevHighlight = highlightedCueKey
            ?: cues.getOrNull(currentCueIndex)?.identityKey()
        val prevScroll = scrollTargetKey
            ?: cues.getOrNull(scrollTargetIndex)?.identityKey()

        cues = newCues

        if (prevHighlight != null) {
            val highlightIndex = newCues.indexOfFirst { it.identityKey() == prevHighlight }
            if (highlightIndex >= 0) {
                currentCueIndex = highlightIndex
                // Keep the same key instance/string — no highlight recomposition flash.
                if (highlightedCueKey != prevHighlight) {
                    highlightedCueKey = prevHighlight
                }

                if (prevScroll != null) {
                    val scrollIndex = newCues.indexOfFirst { it.identityKey() == prevScroll }
                    if (scrollIndex >= 0) {
                        scrollTargetIndex = scrollIndex
                        if (scrollTargetKey != prevScroll) {
                            scrollTargetKey = prevScroll
                        }
                        return
                    }
                }
                // Scroll identity gone (rare) — recompute lead from current highlight.
                updateScrollTarget()
                return
            }
        }
        updateCurrentCueIndexFromPlayer()
    }


    /** Update current cue + highlight key. Highlight key only changes when identity changes. */
    private fun commitCurrentCueIndex(index: Int) {
        currentCueIndex = index
        val key = cues.getOrNull(index)?.identityKey()
        if (highlightedCueKey != key) {
            highlightedCueKey = key
        }
    }

    /** Update scroll target index + key. Key only changes when identity changes. */
    private fun commitScrollTargetIndex(index: Int) {
        scrollTargetIndex = index
        val key = cues.getOrNull(index)?.identityKey()
        if (scrollTargetKey != key) {
            scrollTargetKey = key
        }
    }

    private fun TimedCue.identityKey(): String = "$startMs|$endMs|$text"

    private fun trackSignature(): String {
        val selected = player.currentTracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_TEXT && it.isSelected
        } ?: return "none"
        val format = selected.getTrackFormat(0)
        return listOf(
            player.currentMediaItem?.mediaId.orEmpty(),
            player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty(),
            format.id.orEmpty(),
            format.language.orEmpty(),
            format.label.orEmpty(),
            format.sampleMimeType.orEmpty(),
            format.codecs.orEmpty(),
        ).joinToString("|")
    }
}

private fun String.normalizeCueText(): String =
    trim()
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""</?[^>]+>"""), "")
        .replace(Regex("""\{[^}]*\}"""), "")
