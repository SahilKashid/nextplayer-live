# Live subtitles panel

The landscape live-subtitles side panel builds a full `(startMs, endMs, text)` cue timeline for the selected text track.

## Sources

- **External** subtitle files attached as `SubtitleConfiguration` URIs: SubRip (SRT) and WebVTT are parsed by `SubtitleCueParser`; ASS/SSA and TTML fall back to Media3 `DefaultSubtitleParserFactory` when present as standalone files.
- **Embedded** text tracks inside containers (MKV/MP4/…): `EmbeddedSubtitleCueExtractor` demuxes the media with Media3 `DefaultExtractorsFactory` (text-track transcoding enabled) on a background IO dispatcher, matches the selected `Format` (id / language / label / original mime / order), and converts `CuesWithTiming` into `TimedCue`s. Non-selected text tracks and bitmap tracks discard samples early.

Player / `MediaController` APIs (`currentTracks`, `currentMediaItem`, `currentCues`) are only read on the main application thread; demux and file I/O stay on `Dispatchers.IO`.

## Highlight sync

The active cue is driven primarily from Media3 `EVENT_CUES` / `player.currentCues` (same path as the on-video overlay). Text is matched against the loaded `TimedCue` list so the panel stays in lockstep with nextlib's delay-adjusted `NextTextRenderer`. A ~50ms position tick while the panel is open is used only as a fallback between cues. Position fallback applies OffsetRenderer semantics (`position * speed - delay`); EVENT_CUES matching does **not** double-apply delay.

## Cache

Cue timelines are cached in a session LRU and on disk under `context.subtitleCacheDir` as `live_cues_*.json`, keyed by media id/URI + track signature (+ file length/lastModified when available). Cache hits paint immediately without a full-panel spinner.

## Unsupported

Image-based subtitles (**PGS**, **VobSub**, **DVB**) cannot be turned into a text list. The panel shows the unsupported empty state for those tracks.
