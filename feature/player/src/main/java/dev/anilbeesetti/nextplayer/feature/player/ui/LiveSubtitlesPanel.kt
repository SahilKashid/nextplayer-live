package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import dev.anilbeesetti.nextplayer.feature.player.state.LiveSubtitlesState
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private val ScrollAnimation = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)
private val HighlightAnimation = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * Right-side live subtitles timeline for landscape playback.
 *
 * Auto-scrolls so the upcoming/active cue is vertically centered while
 * [LiveSubtitlesState.isFollowing] is true. Scroll leads the bold highlight
 * slightly so the slide feels on-time. User scrolling pauses follow for ~3s
 * (or until "jump to current").
 */
@Composable
fun LiveSubtitlesPanel(
    state: LiveSubtitlesState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Highlight leads with scrollTargetIndex (same ~380ms head start as the slide).
    val highlightIndex = state.scrollTargetIndex
    val density = LocalDensity.current
    val userScrollConnection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    state.onUserScroll()
                }
                return Offset.Zero
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 3.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val halfViewportPx = constraints.maxHeight / 2
            val halfViewportDp = with(density) { halfViewportPx.toDp() }

            // Scroll + highlight share scrollTargetIndex. collectLatest cancels an
            // in-flight slide when the target jumps again (fast scenes); short cue
            // gaps snap instead of stacking incomplete animations.
            var rapidFollow by remember { mutableStateOf(false) }

            LaunchedEffect(state.isFollowing, halfViewportPx) {
                if (!state.isFollowing) return@LaunchedEffect
                snapshotFlow {
                    val index = state.scrollTargetIndex
                    val identity = state.cues.getOrNull(index)?.identityKey()
                    index to identity
                }
                    .distinctUntilChanged()
                    .collectLatest { (index, identity) ->
                        if (!state.isFollowing) return@collectLatest
                        if (identity == null || index !in state.cues.indices) return@collectLatest
                        if (state.cues[index].identityKey() != identity) return@collectLatest
                        if (listState.isItemNearViewportCenter(index)) {
                            rapidFollow = false
                            return@collectLatest
                        }
                        val snap = state.cues.isRapidGapTo(index)
                        rapidFollow = snap
                        listState.centerItemInViewport(index, animated = !snap)
                        rapidFollow = false
                    }
            }

            when {
                state.isLoading && state.cues.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }

                state.cues.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (state.isUnsupportedTrack) {
                                    R.string.live_subtitles_unsupported
                                } else {
                                    R.string.live_subtitles_empty
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    // Half-viewport padding lets first/last cues scroll to true center.
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(userScrollConnection),
                        state = listState,
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = halfViewportDp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(
                            items = state.cues,
                            key = { _, cue -> cue.identityKey() },
                        ) { index, cue ->
                            LiveSubtitleCueRow(
                                cue = cue,
                                isCurrent = index == highlightIndex,
                                animateHighlight = !rapidFollow,
                                onClick = { state.seekToCue(cue) },
                            )
                        }
                    }
                }
            }

            // Floating close / jump controls — no full header bar.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.isFollowing && state.cues.isNotEmpty()) {
                    IconButton(onClick = state::jumpToCurrent) {
                        Icon(
                            imageVector = NextIcons.Focus,
                            contentDescription = stringResource(R.string.jump_to_current_cue),
                        )
                    }
                }
                IconButton(onClick = { state.updatePanelVisible(false) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.hide_live_subtitles),
                    )
                }
            }
        }
    }
}

private const val NearCenterTolerancePx = 8f
/** Cue start gaps below this use snap-follow instead of a slide. */
private const val RapidCueGapMs = 450L

private fun TimedCue.identityKey(): String = "$startMs|$endMs|$text"

private fun List<TimedCue>.isRapidGapTo(index: Int): Boolean {
    if (index <= 0 || index !in indices) return false
    return (this[index].startMs - this[index - 1].startMs) < RapidCueGapMs
}

private fun LazyListState.isItemNearViewportCenter(index: Int): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val viewportCenter =
        (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val itemCenter = item.offset + item.size / 2f
    return abs(itemCenter - viewportCenter) <= NearCenterTolerancePx
}

private fun LazyListState.itemCenterDelta(index: Int): Float? {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val viewportCenter =
        (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    return item.offset + item.size / 2f - viewportCenter
}

/**
 * Center [index] in the viewport. Animated slides are used for normal pacing;
 * rapid cue bursts snap so cancelled mid-slides don't flicker.
 */
private suspend fun LazyListState.centerItemInViewport(index: Int, animated: Boolean) {
    if (isItemNearViewportCenter(index)) return

    val alreadyVisible = layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!alreadyVisible) {
        scrollToItem(index)
    }

    val delta = itemCenterDelta(index) ?: return
    if (abs(delta) <= NearCenterTolerancePx) return
    if (animated) {
        animateScrollBy(delta, animationSpec = ScrollAnimation)
    } else {
        scrollBy(delta)
    }
}

@Composable
private fun LiveSubtitleCueRow(
    cue: TimedCue,
    isCurrent: Boolean,
    animateHighlight: Boolean,
    onClick: () -> Unit,
) {
    val colorSpec = if (animateHighlight) {
        tween<Color>(durationMillis = 200, easing = FastOutSlowInEasing)
    } else {
        tween<Color>(durationMillis = 0)
    }
    val floatSpec = if (animateHighlight) HighlightAnimation else tween<Float>(durationMillis = 0)
    val background by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        },
        animationSpec = colorSpec,
        label = "cueBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = colorSpec,
        label = "cueContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.03f else 1f,
        animationSpec = floatSpec,
        label = "cueScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.72f,
        animationSpec = floatSpec,
        label = "cueAlpha",
    )
    val timeLabel = remember(cue.startMs) { Utils.formatDurationMillis(cue.startMs) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.size(2.dp))
        Text(
            text = cue.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = contentColor,
        )
    }
}
