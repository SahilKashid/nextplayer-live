package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's data source factory.
 *
 * - Local `file://` (and path-resolvable `content://`) media that still looks incomplete
 *   uses [GrowingFileDataSource] so downloads can play while being written (VLC-style).
 * - The same URIs, once settled-complete (tip caught up / real tail, mtime not growing),
 *   use Media3 [DefaultDataSource] so ExoPlayer sees a **finite** length, normal
 *   Matroska cue-seek, and no incomplete wrapper.
 * - Unresolvable `content://` media uses [GrowingContentDataSource] (PFD / AFD with
 *   [androidx.media3.common.C.LENGTH_UNSET]) — never Media3's fixed-length ContentDataSource
 *   for video playback when growing support is desired.
 * - Other local / http(s) media uses Media3 [DefaultDataSource].
 * - `smb`/`ftp`/`sftp`/`webdav` use [NetworkDataSource].
 *
 * ## Growing-file limitations
 * Best with streamable containers (MKV, TS, many progressive downloads). MP4/MOV without an
 * early `moov` may need load retries until metadata is present (player
 * `GrowingFileLoadErrorHandlingPolicy`). Duration/seek range may grow as more media is parsed.
 * `content://` is supported via the growing content source when a filesystem path cannot be
 * resolved.
 */
@UnstableApi
@Singleton
class NextDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: NetworkSessions,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = SchemeDispatchingDataSource(
        context = context,
        growingFile = GrowingFileDataSource.Factory().createDataSource(),
        growingContent = GrowingContentDataSource.Factory(context).createDataSource(),
        default = DefaultDataSource.Factory(context).createDataSource(),
        network = NetworkDataSource(sessions),
    )

    /** Disconnects the network client held for playback. Call when the player is released. */
    suspend fun release() = sessions.release()
}

/**
 * Picks the delegate on the first [open], since the scheme is only known then. Media3 asks the
 * factory for a source before it knows which item it will play.
 */
@UnstableApi
private class SchemeDispatchingDataSource(
    private val context: Context,
    private val growingFile: DataSource,
    private val growingContent: DataSource,
    private val default: DataSource,
    private val network: DataSource,
) : DataSource {

    private var delegate: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val (target, effectiveSpec) = when {
            NetworkUri.isNetworkUri(uri) -> network to dataSpec
            isFileUri(uri) -> {
                val path = GrowingFileDataSource.resolvePath(uri)
                if (path != null && File(path).canRead() &&
                    !IncompleteLocalMedia.shouldPlayAsGrowing(path)
                ) {
                    default to dataSpec
                } else {
                    growingFile to dataSpec
                }
            }
            isContentUri(uri) -> {
                val path = context.getPath(uri)
                if (path != null && File(path).canRead()) {
                    if (!IncompleteLocalMedia.shouldPlayAsGrowing(path)) {
                        // Finished: finite-length DefaultDataSource (File/Content).
                        default to dataSpec
                    } else {
                        // Rewrite to file:// so GrowingFileDataSource can open via RandomAccessFile.
                        growingFile to dataSpec.withUri(File(path).toUri())
                    }
                } else {
                    // Path unresolved — grow via ContentResolver AFD/PFD, never fixed ContentDataSource.
                    growingContent to dataSpec
                }
            }
            else -> default to dataSpec
        }
        delegate = target
        return target.open(effectiveSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "read() before open()" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        growingFile.addTransferListener(transferListener)
        growingContent.addTransferListener(transferListener)
        default.addTransferListener(transferListener)
        network.addTransferListener(transferListener)
    }

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    private fun isFileUri(uri: Uri): Boolean =
        ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true) ||
            (uri.scheme.isNullOrEmpty() && uri.path?.startsWith("/") == true)

    private fun isContentUri(uri: Uri): Boolean =
        ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)
}
