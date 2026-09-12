# Live subtitles panel

The landscape live-subtitles side panel builds a full `(startMs, endMs, text)` cue timeline for the selected text track.


Shipped in **v1.0.3**: false-unsupported fix for empty text tracks (don’t cache empty cue
lists; ASS/SSA retry), WebVTT panel listing for sideloaded `.vtt`, overlay together with the
live panel (default on), and subtitle vertical position (including bottom-anchored VTT lift).

Shipped in **v1.0.4**: live panel stays open across pause / screen lock / recreate
(`liveSubtitlesPanelOpen`); scrolling cues while paused no longer jumps back after ~3s
(auto-follow only while playing; jump-to-current still works).

## Sources

- **External** subtitle files attached as `SubtitleConfiguration` URIs: SubRip (SRT) and **WebVTT (`.vtt`)** are parsed by `SubtitleCueParser` (including headerless VTT, NOTE/STYLE/REGION blocks, optional hours, multiline cues, `<v>` voice spans, and settings after `-->`). ASS/SSA and TTML fall back to Media3 `DefaultSubtitleParserFactory` when present as standalone files. MIME is taken from the URI extension when possible (`text/vtt` for `.vtt`), with content sniffing and `Format.codecs` fallbacks when `content://` paths omit the extension.
- **Embedded** text tracks inside containers (MKV/MP4/…), including in-container WebVTT: `EmbeddedSubtitleCueExtractor` demuxes the media with Media3 `DefaultExtractorsFactory` (text-track transcoding enabled) on a background IO dispatcher, matches the selected `Format` (id / language / label / original mime / order), and converts `CuesWithTiming` into `TimedCue`s. Non-selected text tracks and bitmap tracks discard samples early. While demuxing, partial cue lists are published to the UI (~every 50 cues / 200ms). When playback is mid-file and a `SeekMap` is available, extraction seeks near the current position first (phase A) so the panel paints quickly, then fills earlier cues from the start (phase B) and merges/dedupes before caching. If the transcoded pass returns no cues for a text track, a second demux retries with raw subtitle samples and Matroska EOF cue-seeking disabled (helps some ASS/SSA/SRT-in-MKV cases).

Sideloaded tracks are often exposed by Media3 as `APPLICATION_MEDIA3_CUES` with the original mime in `Format.codecs` (e.g. `text/vtt`). `SubtitleCueLoader.resolveExternalSubtitleUri` matches that original mime against `SubtitleConfiguration.mimeType` so a selected `.vtt` file is read directly — the panel must not demux the video container for an external VTT (that yields an empty list while the on-video overlay still works).

Player / `MediaController` APIs (`currentTracks`, `currentMediaItem`, `currentCues`, `currentPosition`) are only read on the main application thread; demux and file I/O stay on `Dispatchers.IO`.

## Highlight sync

The active cue is driven primarily from Media3 `EVENT_CUES` / `player.currentCues` (same path as the on-video overlay). Text is matched against the loaded `TimedCue` list so the panel stays in lockstep with nextlib's delay-adjusted `NextTextRenderer`. A ~50ms position tick while the panel is open is used only as a fallback between cues. Position fallback applies OffsetRenderer semantics (`position * speed - delay`); EVENT_CUES matching does **not** double-apply delay.

## Cache

Cue timelines are cached in a session LRU and on disk under `context.subtitleCacheDir` as compact `live_cues_*.bin` files (legacy JSON is migrated on read), keyed by media id/URI + track signature (+ file length/lastModified when available). Memory hits paint immediately; disk hits avoid a full demux spinner.

**Empty results are never cached.** A one-shot demux/parse miss (wrong track match, transient I/O, ASS sample miss) must be retried on the next open — otherwise the panel would stick on empty until the cache file was cleared. Legacy empty `live_cues_*.bin` files are ignored and deleted on read.

## Unsupported

Image-based subtitles (**PGS**, **VobSub**, **DVB**) cannot be turned into a text list. The panel shows the unsupported empty state **only** for those bitmap mime/codec tracks (`EmbeddedSubtitleCueExtractor.isBitmapSubtitle`).

An empty cue list for a **text** track (SRT/ASS/SSA/**VTT**, including when demux finds nothing yet) shows the generic empty message (`live_subtitles_empty`), not the image-based unsupported string. OCR for PGS/VobSub/DVB is out of scope.

## Overlay with the live panel

The landscape panel no longer forces the on-video `SubtitleView` overlay off.

`PlayerPreferences.showOverlaySubtitlesWithLivePanel` (default **true**) controls whether the normal overlay stays visible while the panel is open. When the panel is closed, the overlay is always shown. Toggle this in **Settings → Subtitle → Show on-screen subtitles with live panel**.

## Overlay vertical position

On-screen (overlay) subtitles default to the current Media3 bottom placement (`subtitleVerticalPosition = 0`, plus Media3's 8% `DEFAULT_BOTTOM_PADDING_FRACTION` for cues without a line).

**Settings → Subtitle → Subtitle vertical position** is a slider from **Bottom** (default) toward the center (`0`–`0.5`). The player:

1. Calls `SubtitleView.setBottomPaddingFraction(0.08 + position)` so SRT / unset-line cues lift immediately.
2. Rewrites WebVTT automatic / bottom-anchored cues (`line = -1` number, or a fraction ≥ 0.85) to a matching fractional line with `ANCHOR_TYPE_END`. Media3 ignores bottom padding for spec-defined VTT lines, so this is what actually moves `.vtt` overlay text.

Intentionally high VTT lines (for example `line:10%`) and bitmap cues are left alone. Changing the slider updates the overlay live through `SubtitleConfiguration.verticalPosition`. The live panel's own cue list is not affected.

## Panel open persistence

`PlayerPreferences.liveSubtitlesPanelOpen` (default **false**) remembers whether the user left the live panel open. The player toggle and panel close control write this pref; on activity recreate (pause / screen lock) the panel restores when landscape again.

Portrait / non-landscape only hides the panel via the `showLiveSubtitlesPanel = isPanelVisible && isLandscape && !isTv` predicate — it does **not** clear `isPanelVisible`, so rotating back to landscape brings the panel back if it was open.

## Auto-follow while paused

Scrolling the cue list unfollows live centering. While **playing**, follow resumes after ~3s (`LiveSubtitlesAutoFollowResumeDelay`). While **paused**, follow never auto-resumes — the list stays where you scrolled. A pending resume is cancelled on pause. When playback **starts** again, follow resumes immediately (YouTube-like catch-up). The jump-to-current button always re-enables follow, including while paused.
