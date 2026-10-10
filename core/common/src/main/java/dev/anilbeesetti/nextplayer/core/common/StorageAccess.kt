package dev.anilbeesetti.nextplayer.core.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision

/**
 * Whether this process already has broad filesystem access needed to list/read
 * non-media sidecar files (subtitle `.srt`/`.vtt` next to videos).
 *
 * On API 29 and below, [storagePermission] (READ/WRITE_EXTERNAL_STORAGE) is enough.
 * On API 30+, video/photo media permissions do not cover sibling subtitle files —
 * [Environment.isExternalStorageManager] (All files access) is required.
 */
fun Context.hasAllFilesAccess(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
    return try {
        Environment.isExternalStorageManager()
    } catch (_: Throwable) {
        // Some test / OEM environments throw here; treat as not granted.
        false
    }
}

/**
 * True when the device is on API 30+ and All files access has not been granted.
 */
fun Context.needsAllFilesAccess(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()
}

/**
 * Builds an intent to the system All files access settings page for this app.
 * Returns null when the settings activity cannot be resolved (common on some TVs).
 */
fun Context.createManageAllFilesIntent(): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

    return try {
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        if (appIntent.resolveActivity(packageManager) != null) {
            return appIntent
        }

        val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        generalIntent.takeIf { it.resolveActivity(packageManager) != null }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Whether we should prompt for All files access: needed, not TV, and settings
 * page is available. TVs often lack the manage-all-files settings UI.
 */
fun Context.canRequestAllFilesAccess(): Boolean {
    return needsAllFilesAccess() && !isTelevision && createManageAllFilesIntent() != null
}
