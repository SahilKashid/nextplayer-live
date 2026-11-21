package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.ExtractorsFactory
import dev.anilbeesetti.nextplayer.feature.player.utils.apiHeaders
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.withFfmpegSubtitles

/**
 * Creates independent sources so headers follow an item, including manifests, segments and subtitles.
 *
 * Incomplete local files use [GrowingAwareExtractorsFactory] (no EOF cue seek, cluster index).
 * Settled files keep stock FFmpeg subtitle extraction.
 */
@UnstableApi
internal class ApiMediaSourceFactory(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory by DefaultMediaSourceFactory(
    context,
).setDataSourceFactory(dataSourceFactory) {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val sources = RequestHeadersDataSourceFactory(
            dataSourceFactory,
            mediaItem.apiHeaders,
        )
        val uri = mediaItem.localConfiguration?.uri
        val growing = uri != null && GrowingAwareExtractorsFactory.isLikelyGrowing(
            context,
            uri,
        )
        val extractors: ExtractorsFactory = if (growing) {
            GrowingAwareExtractorsFactory(context)
        } else {
            FfmpegSubtitleExtractorsFactory()
        }
        val factory = DefaultMediaSourceFactory(
            context,
            extractors,
        )
            .setDataSourceFactory(sources)
        val withSubtitles = if (growing) {
            factory
        } else {
            factory.withFfmpegSubtitles(sources)
        }
        return withSubtitles
            .setLoadErrorHandlingPolicy(GrowingFileLoadErrorHandlingPolicy(context))
            .createMediaSource(mediaItem)
    }
}

@UnstableApi
internal class RequestHeadersDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val headers: Map<String, String>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val source = delegate.createDataSource()
        return object : DataSource by source {
            override fun open(dataSpec: DataSpec): Long = source.open(
                if (dataSpec.uri.scheme in listOf(
                        "http",
                        "https",
                    )
                ) {
                    dataSpec.buildUpon().setHttpRequestHeaders(
                        headers + dataSpec.httpRequestHeaders,
                    ).build()
                } else {
                    dataSpec
                },
            )
        }
    }
}
