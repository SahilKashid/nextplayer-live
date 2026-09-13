# Live subtitles panel

The landscape live-subtitles side panel builds a full `(startMs, endMs, text)` cue timeline for the selected text track.


Shipped in **v1.0.3**: false-unsupported fix for empty text tracks (don’t cache empty cue
lists; ASS/SSA retry), WebVTT panel listing for sideloaded `.vtt`, overlay together with the
live panel (default on), and subtitle vertical position (including bottom-anchored VTT lift).

Shipped in **v1.0.4**: live panel stays open across pause / screen lock / recreate
(`liveSubtitlesPanelOpen`); scrolling cues while paused no longer jumps back after ~3s
(auto-follow only while playing; jump-to-current still works).

Shipped in **v1.0.5**: expanding-window progressive demux (seed near playhead, grow earlier/later
bands with frequent partial emits instead of a Phase-A / Phase-B cliff) and hardened cue cache
(final-only puts, stable keys without flaky mtime, larger LRU, fsync+rename, corrupt-file delete
for reliable reopen hits). Anti-stutter scroll/highlight identity path unchanged.

Shipped in **v1.0.6**: fresh-install defaults (system theme + high-contrast dark, remember
brightness, double-tap play/pause, long-press 2x, subtitle vertical 20% toward center, preferred
subtitle language English); sidecar auto-discovery (`.vtt/.srt/.ass/.ssa/.ttml`) with all-files
access, path-probe fallback, and rescan on every open; auto-select a subtitle when any track
exists (prefer English — never leave Disable if tracks present); selector labels decode `%20`
to spaces; live panel scroll re-anchors on next/prev media (fresh `LazyListState` + playhead nearest-cue).

## Sources

- **External** subtitle files attached as `SubtitleConfiguration` URIs: SubRip (SRT) and **WebVTT (`.vtt`)** are parsed by `SubtitleCueParser` (including headerless VTT, NOTE/STYLE/REGION blocks, optional hours, multiline cues, `<v>` voice spans, and settings after `-->`). ASS/SSA and TTML fall back to Media3 `DefaultSubtitleParserFactory` when present as standalone files. MIME is taken from the URI extension when possible (`text/vtt` for `.vtt`), with content sniffing and `Format.codecs` fallbacks when `content://` paths omit the extension.
- **Embedded** text tracks inside containers (MKV/MP4/…), including in-container WebVTT: `EmbeddedSubtitleCueExtractor` demuxes the media with Media3 `DefaultExtractorsFactory` (text-track transcoding enabled) on a background IO dispatcher, matches the selected `Format` (id / language / label / original mime / order), and converts `CuesWithTiming` into `TimedCue`s. Non-selected text tracks and bitmap tracks discard samples early. While demuxing, partial cue lists are published to the UI often (~every 25 cues / 120ms) so the panel grows steadily. When playback is mid-file and a `SeekMap` is seekable, extraction uses an **expanding-window** strategy (`ExpandingCueWindow`): seed a modest band around the playhead (small lead, ~40 cues or ±~40s), emit ASAP, then iteratively expand earlier and later with growing steps (~45s then +30s …) until full coverage / EOF — many small updates instead of a harsh Phase-A-then-Phase-B dump. Merge/dedupe is by cue identity; later partials never shrink the list (`LiveSubtitlesState` prefers larger snapshots). Non-seekable sources or early playback fall back to a single forward pass with the same frequent partial emits. If the transcoded pass returns no cues for a text track, a second demux retries with raw subtitle samples and Matroska EOF cue-seeking disabled (helps some ASS/SSA/SRT-in-MKV cases).

Sideloaded tracks are often exposed by Media3 as `APPLICATION_MEDIA3_CUES` with the original mime in `Format.codecs` (e.g. `text/vtt`). `SubtitleCueLoader.resolveExternalSubtitleUri` matches that original mime against `SubtitleConfiguration.mimeType` so a selected `.vtt` file is read directly — the panel must not demux the video container for an external VTT (that yields an empty list while the on-video overlay still works).

Player / `MediaController` APIs (`currentTracks`, `currentMediaItem`, `currentCues`, `currentPosition`) are only read on the main application thread; demux and file I/O stay on `Dispatchers.IO`.

## Sidecar discovery and storage access

External subtitle files next to a video (`movie.srt`, `movie.en.vtt`, …) are discovered by
`findSubtitleSidecars` in `core/common`. On Android 11+ (especially 13+ with only
`READ_MEDIA_VIDEO`), listing a folder and reading non-media siblings fails — photos/videos
access is not enough. **All files access** (`MANAGE_EXTERNAL_STORAGE` /
`Environment.isExternalStorageManager()`) is required for reliable sidecar discovery.

The app requests All files access after media permission (and again when opening local
playback if still missing). TV builds degrade gracefully when the system settings page is
unavailable. When directory listing returns null/empty, discovery also **probes** exact and
common language-tagged candidate paths without listing.

Every local prepare **re-scans** sidecars (filesystem path from the media library /
`getPath`, never the content `uriString` stored on `VideoState`). After All-files
access is granted mid-session, the player refreshes the current item. Sidecar
labels are URI-decoded (`My%20Movie.en.vtt` → `My Movie.en.vtt`). Preferred
subtitle language defaults to English (`eng`); when any text/sidecar track exists,
one is always selected (preferred match, else first) — never left on Disable.

## Highlight sync

The active cue is driven primarily from Media3 `EVENT_CUES` / `player.currentCues` (same path as the on-video overlay). Text is matched against the loaded `TimedCue` list so the panel stays in lockstep with nextlib's delay-adjusted `NextTextRenderer`. A ~50ms position tick while the panel is open is used only as a fallback between cues. Position fallback applies OffsetRenderer semantics (`position * speed - delay`); EVENT_CUES matching does **not** double-apply delay.

## Cache

Cue timelines are cached in a session LRU (~32 entries) and on disk under `context.subtitleCacheDir` as compact `live_cues_*.bin` files (legacy JSON is migrated on read). Memory hits paint immediately; disk hits avoid a full demux spinner on reopen of the same video+track.

**Only complete final timelines are cached.** `SubtitleCueLoader` calls `LiveSubtitleCueCache.put` after demux/parse finishes with a non-empty list. Progressive `onPartialCues` updates stay in UI state only and must never write the cache.

### Key policy

Prefer stable `mediaId` + track identity (id / language / label / mime / codecs / text-track index). URI strings are normalized (scheme lowercased, stable encoded path/query; fragments dropped) so the same file does not miss.

For `file` / `content` URIs, include **size when known**. **Do not** include `last_modified` — content providers often omit or flake that column, which would churn the key across reopen. When size is unavailable, the size field is left empty consistently.

### Integrity

Binary files use magic + version. Writes go to a temp file, are flushed/`fsync`'d, then renamed. On read failure (corrupt / wrong magic-version), the file is deleted and the next open re-extracts once. Debug cache hit/miss/put counters are logged at `Log.isLoggable(..., DEBUG)` only (not shown in UI).

**Empty results are never cached.** A one-shot demux/parse miss (wrong track match, transient I/O, ASS sample miss) must be retried on the next open — otherwise the panel would stick on empty until the cache file was cleared. Legacy empty `live_cues_*.bin` files are ignored and deleted on read.

## Unsupported

Image-based subtitles (**PGS**, **VobSub**, **DVB**) cannot be turned into a text list. The panel shows the unsupported empty state **only** for those bitmap mime/codec tracks (`EmbeddedSubtitleCueExtractor.isBitmapSubtitle`).

An empty cue list for a **text** track (SRT/ASS/SSA/**VTT**, including when demux finds nothing yet) shows the generic empty message (`live_subtitles_empty`), not the image-based unsupported string. OCR for PGS/VobSub/DVB is out of scope.

## Overlay with the live panel

The landscape panel no longer forces the on-video `SubtitleView` overlay off.

`PlayerPreferences.showOverlaySubtitlesWithLivePanel` (default **true**) controls whether the normal overlay stays visible while the panel is open. When the panel is closed, the overlay is always shown. Toggle this in **Settings → Subtitle → Show on-screen subtitles with live panel**.

## Overlay vertical position

On-screen (overlay) subtitles default to **20% toward center** (`subtitleVerticalPosition = 0.2`, plus Media3's 8% `DEFAULT_BOTTOM_PADDING_FRACTION` for cues without a line). Fresh installs and **Reset settings** use this default; existing installs keep saved values until reset.

**Settings → Subtitle → Subtitle vertical position** is a slider from **Bottom** (`0`) toward the center (`0.5`); the reset control restores the **0.2** default. The player:

1. Calls `SubtitleView.setBottomPaddingFraction(0.08 + position)` so SRT / unset-line cues lift immediately.
2. Rewrites WebVTT automatic / bottom-anchored cues (`line = -1` number, or a fraction ≥ 0.85) to a matching fractional line with `ANCHOR_TYPE_END`. Media3 ignores bottom padding for spec-defined VTT lines, so this is what actually moves `.vtt` overlay text.

Intentionally high VTT lines (for example `line:10%`) and bitmap cues are left alone. Changing the slider updates the overlay live through `SubtitleConfiguration.verticalPosition`. The live panel's own cue list is not affected.

## Panel open persistence

`PlayerPreferences.liveSubtitlesPanelOpen` (default **false**) remembers whether the user left the live panel open. The player toggle and panel close control write this pref; on activity recreate (pause / screen lock) the panel restores when landscape again.

Portrait / non-landscape only hides the panel via the `showLiveSubtitlesPanel = isPanelVisible && isLandscape && !isTv` predicate — it does **not** clear `isPanelVisible`, so rotating back to landscape brings the panel back if it was open.

## Media item / track change (next/prev)

When the media item or selected text-track signature changes, `LiveSubtitlesState` clears cues / highlight / scroll identities, re-enables follow (`isFollowing = true`), and bumps `listResetKey`. The panel keys `LazyListState` on that key so the previous video's offset is dropped; the first cue-list apply (cache hit or progressive seed) then anchors scroll/highlight via `nearestCueIndexByPlayhead` to the new playhead's time zone immediately — even with empty `currentCues` (top only when the playhead is near the start). Progressive fills **within** the same signature keep anti-stutter identity preservation and still re-resolve by playhead while following. Panel open preference is unchanged across videos.

## Auto-follow while paused

Scrolling the cue list unfollows live centering. While **playing**, follow resumes after ~3s (`LiveSubtitlesAutoFollowResumeDelay`). While **paused**, follow never auto-resumes — the list stays where you scrolled. A pending resume is cancelled on pause. When playback **starts** again, follow resumes immediately (YouTube-like catch-up). The jump-to-current button always re-enables follow, including while paused.
