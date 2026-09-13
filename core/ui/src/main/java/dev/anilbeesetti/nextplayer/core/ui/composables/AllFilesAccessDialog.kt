package dev.anilbeesetti.nextplayer.core.ui.composables

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.preview.DayNightPreview
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme

/**
 * Prompts for All files access so sidecar subtitles next to videos can be listed/read.
 * Dismissible — media browsing still works with only READ_MEDIA_VIDEO.
 */
@Composable
fun AllFilesAccessDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(text = stringResource(R.string.all_files_access_title))
        },
        text = {
            Text(text = stringResource(R.string.all_files_access_info))
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.grant_permission))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.all_files_access_not_now))
            }
        },
    )
}

@DayNightPreview
@Composable
fun AllFilesAccessDialogPreview() {
    NextPlayerTheme {
        Surface {
            AllFilesAccessDialog(
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
