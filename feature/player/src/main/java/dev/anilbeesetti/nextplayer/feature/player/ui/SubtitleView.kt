package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.anilbeesetti.nextplayer.core.model.Font
import dev.anilbeesetti.nextplayer.feature.player.extensions.toTypeface
import dev.anilbeesetti.nextplayer.feature.player.state.rememberCuesState

@OptIn(UnstableApi::class)
@Composable
fun SubtitleView(
    modifier: Modifier = Modifier,
    player: Player,
    isInPictureInPictureMode: Boolean,
    configuration: SubtitleConfiguration,
) {
    val cuesState = rememberCuesState(player)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SubtitleView(context).apply {
                applySubtitleViewConfiguration(
                    subtitleView = this,
                    configuration = configuration,
                    isInPictureInPictureMode = isInPictureInPictureMode,
                )
            }
        },
        update = { subtitleView ->
            applySubtitleViewConfiguration(
                subtitleView = subtitleView,
                configuration = configuration,
                isInPictureInPictureMode = isInPictureInPictureMode,
            )
            subtitleView.setCues(
                SubtitleVerticalPosition.applyToCues(
                    cues = cuesState.cues,
                    verticalPosition = configuration.verticalPosition,
                ),
            )
        },
    )
}

@OptIn(UnstableApi::class)
private fun applySubtitleViewConfiguration(
    subtitleView: SubtitleView,
    configuration: SubtitleConfiguration,
    isInPictureInPictureMode: Boolean,
) {
    val captioningManager = getSystemService(subtitleView.context, CaptioningManager::class.java)
    if (configuration.useSystemCaptionStyle && captioningManager != null) {
        subtitleView.setStyle(CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle))
    } else {
        val userStyle = CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.BLACK.takeIf { configuration.showBackground } ?: android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            android.graphics.Color.BLACK,
            Typeface.create(
                configuration.font.toTypeface(),
                Typeface.BOLD.takeIf { configuration.textBold } ?: Typeface.NORMAL,
            ),
        )
        subtitleView.setStyle(userStyle)
    }
    if (isInPictureInPictureMode) {
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    } else {
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
    }
    subtitleView.setApplyEmbeddedStyles(configuration.applyEmbeddedStyles)
    subtitleView.setBottomPaddingFraction(
        SubtitleVerticalPosition.bottomPaddingFraction(configuration.verticalPosition),
    )
}

@Stable
data class SubtitleConfiguration(
    val useSystemCaptionStyle: Boolean,
    val showBackground: Boolean,
    val font: Font,
    val textSize: Int,
    val textBold: Boolean,
    val applyEmbeddedStyles: Boolean,
    val verticalPosition: Float = 0f,
)
