package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.anilbeesetti.nextplayer.core.model.Font
import dev.anilbeesetti.nextplayer.feature.player.extensions.toTypeface

@OptIn(UnstableApi::class)
@Composable
fun SubtitleView(
    modifier: Modifier = Modifier,
    player: Player,
    isInPictureInPictureMode: Boolean,
    configuration: SubtitleConfiguration,
) {
    // Apply cues from the player listener. Compose snapshot state would post the
    // view update to the next main-thread message, so on-video text lagged the audio.
    val cueOutput = remember { DirectOnVideoSubtitleOutput() }
    cueOutput.verticalPosition = configuration.verticalPosition

    DisposableEffect(player, cueOutput) {
        player.addListener(cueOutput)
        cueOutput.setCues(player.currentCues.cues)
        onDispose { player.removeListener(cueOutput) }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SubtitleView(context).also { view ->
                cueOutput.view = view
                applySubtitleViewConfiguration(
                    subtitleView = view,
                    configuration = configuration,
                    isInPictureInPictureMode = isInPictureInPictureMode,
                )
                cueOutput.setCues(player.currentCues.cues)
            }
        },
        onRelease = { released ->
            if (cueOutput.view === released) cueOutput.view = null
        },
        update = { subtitleView ->
            cueOutput.view = subtitleView
            applySubtitleViewConfiguration(
                subtitleView = subtitleView,
                configuration = configuration,
                isInPictureInPictureMode = isInPictureInPictureMode,
            )
            cueOutput.setCues(player.currentCues.cues)
        },
    )
}

/**
 * Pushes the player's cue group straight onto [SubtitleView]. [Player.Listener.onCues]
 * runs on the application looper inside the player event, with no Compose frame in between.
 */
@UnstableApi
internal class DirectOnVideoSubtitleOutput : Player.Listener {
    var view: SubtitleView? = null
    var verticalPosition: Float = 0f

    override fun onCues(cueGroup: CueGroup) {
        setCues(cueGroup.cues)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Player also delivers CueGroup. Kept so a list-only callback is not dropped.")
    override fun onCues(cues: List<Cue>) {
        setCues(cues)
    }

    fun setCues(cues: List<Cue>) {
        view?.setCues(
            SubtitleVerticalPosition.applyToCues(
                cues = cues,
                verticalPosition = verticalPosition,
            ),
        )
    }
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
