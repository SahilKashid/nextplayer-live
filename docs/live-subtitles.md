# Live subtitles panel

The landscape live-subtitles side panel builds a full `(startMs, endMs, text)` cue timeline for the selected text track.

## Sources

- **External** subtitle files attached as `SubtitleConfiguration` URIs: SubRip (SRT) and WebVTT are parsed by `SubtitleCueParser`; ASS/SSA and TTML fall back to Media3 `DefaultSubtitleParserFactory` when present as standalone files.
- **Embedded** text tracks inside containers (MKV/MP4/…): `EmbeddedSubtitleCueExtractor` demuxes the media with Media3 `DefaultExtractorsFactory` (text-track transcoding enabled) on a background IO dispatcher, matches the selected `Format` (id / language / label / original mime / order), and converts `CuesWithTiming` into `TimedCue`s.

Player / `MediaController` APIs (`currentTracks`, `currentMediaItem`) are only read on the main application thread; demux and file I/O stay on `Dispatchers.IO`.

## Unsupported

Image-based subtitles (**PGS**, **VobSub**, **DVB**) cannot be turned into a text list. The panel shows the unsupported empty state for those tracks.
