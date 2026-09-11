package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

            // Scroll + highlight both use scrollTargetIndex (~380ms lead) so the
            // color change and center slide land together on the upcoming cue.
            val scrollIndex = state.scrollTargetIndex
            val scrollCue = state.cues.getOrNull(scrollIndex)
            val scrollIdentity = scrollCue?.identityKey()
            var lastScrolledIdentity by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(scrollIdentity, state.isFollowing, halfViewportPx) {
                if (!state.isFollowing) return@LaunchedEffect
                if (scrollIdentity == null) return@LaunchedEffect
                val index = state.scrollTargetIndex
                if (index !in state.cues.indices) return@LaunchedEffect
                val cue = state.cues[index]
                if (cue.identityKey() != scrollIdentity) return@LaunchedEffect

                // Identity unchanged and we already scrolled to it — do not restart.
                if (scrollIdentity == lastScrolledIdentity &&
                    listState.isItemNearViewportCenter(index)
                ) {
                    return@LaunchedEffect
                }

                listState.animateItemCenterToViewportCenter(index)
                lastScrolledIdentity = scrollIdentity
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

private fun TimedCue.identityKey(): String = "$startMs|$endMs|$text"

private fun LazyListState.isItemNearViewportCenter(index: Int): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val viewportCenter =
        (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val itemCenter = item.offset + item.size / 2f
    return abs(itemCenter - viewportCenter) <= NearCenterTolerancePx
}

/**
 * One smooth center animation: bring the item into view instantly if needed, then
 * a single [animateScrollBy] so the cue midpoint lands at the viewport center.
 */
private suspend fun LazyListState.animateItemCenterToViewportCenter(index: Int) {
    if (isItemNearViewportCenter(index)) return

    val alreadyVisible = layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!alreadyVisible) {
        // Instant jump into view — avoids a second competing scroll animation.
        scrollToItem(index)
    }

    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter =
        (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val itemCenter = item.offset + item.size / 2f
    val delta = itemCenter - viewportCenter
    if (abs(delta) > NearCenterTolerancePx) {
        animateScrollBy(delta, animationSpec = ScrollAnimation)
    }
}

@Composable
private fun LiveSubtitleCueRow(
    cue: TimedCue,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "cueBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "cueContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.03f else 1f,
        animationSpec = HighlightAnimation,
        label = "cueScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.72f,
        animationSpec = HighlightAnimation,
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
