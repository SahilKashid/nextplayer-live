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
to spaces.

Shipped in **v1.0.7**: on next/prev media, the live panel snaps immediately to the new
playhead’s cue zone (`listResetKey` + `nearestCueIndexByPlayhead`) even if no cue is
active on that frame — no stuck previous-video scroll offset.

Shipped in **v1.1.1**: the landscape button cycles off → right → left → off; the rotate
button closes the panel before changing orientation; on-video subtitles stay independent
of the panel (`showOnVideoSubtitles`). Picker **Off** hides the on-video view and leaves
the selected text track enabled so the panel can still list its cues. Left and right
docks share one layout (`LiveSubtitlesSplit`): the panel is 35% of the width on the
chosen physical side, and the video, on-video subtitles, and controls use the rest.

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

## Button cycle and side

The landscape live-subtitles button (`ControlsTopView`, hidden in portrait and on TV) cycles three states:

1. **Right** — panel on, docked on the physical right. The panel is 35% of the width and the video is the rest.
2. **Left** — the same split, panel on the physical left. Video, on-video subtitles, and the controls overlay occupy the space to the right of the panel.
3. **Off** — panel hidden, video uses the full width.

Both docks are `LiveSubtitlesSplit` with the side as a parameter. The panel and the player are measured to those rectangles and placed on the physical left or right, so the panel stays the same width on either side and the video and controls start at the panel's inner edge instead of drawing over it.

The next tap after off always starts on the right. `LiveSubtitlesPanelPlacement` is the cycle. The button icon is mirrored when the panel is on the left, and its content description is `show_live_subtitles`, `live_subtitles_on_right`, or `live_subtitles_on_left`.

`PlayerPreferences.liveSubtitlesPanelOpen` still remembers whether the panel was left open (pause / lock / recreate). `liveSubtitlesPanelOnLeft` is restored only while that flag is true. Closing the panel (cycle, panel close control, or rotate) stores both flags false, so a new cycle starts on the right.

Portrait / non-landscape still hides the panel only via `showLiveSubtitlesPanel = isPanelVisible && isLandscape && !isTv`. That predicate does **not** clear `isPanelVisible`, so a configuration change back to landscape restores an open panel and its side. The player's **rotate button** is different: it calls `LiveSubtitlesState.closePanel()` before `RotationState.rotate()`.

## On-video subtitles stay independent

The live panel and the on-video `SubtitleView` are separate:

| Live panel | On-video subtitles | Result |
|------------|--------------------|--------|
| on | on | Side list and overlay |
| on | off | Side list only; overlay hidden |
| off | on | Overlay only |
| off | off | Neither |

`PlayerPreferences.showOnVideoSubtitles` (default **true**) is what `PlayerContentFrame` uses for `showOverlaySubtitles`. It does not look at whether the panel is open. `showOverlaySubtitlesWithLivePanel` remains in the model so older preference JSON still decodes; playback ignores it.

The subtitle track picker is the control. Choosing a track sets `showOnVideoSubtitles` true and `switchTrack(index)`. **Off** sets `showOnVideoSubtitles` false and does **not** call `switchTrack(-1)`. The text track stays selected, so `SubtitleCueLoader.resolveSelectedSubtitle` and `EVENT_CUES` / `currentCues` keep feeding the panel while the overlay view is not composed. The same flag is the **Settings → Subtitle → Show on-screen subtitles** switch.

`applySubtitleSelection` still selects a real text track when any exist (never leave Disable). That keeps panel cues available after Off.

## Overlay vertical position

On-screen (overlay) subtitles default to **20% toward center** (`subtitleVerticalPosition = 0.2`, plus Media3's 8% `DEFAULT_BOTTOM_PADDING_FRACTION` for cues without a line). Fresh installs and **Reset settings** use this default; existing installs keep saved values until reset.

**Settings → Subtitle → Subtitle vertical position** is a slider from **Bottom** (`0`) toward the center (`0.5`); the reset control restores the **0.2** default. The player:

1. Calls `SubtitleView.setBottomPaddingFraction(0.08 + position)` so SRT / unset-line cues lift immediately.
2. Rewrites WebVTT automatic / bottom-anchored cues (`line = -1` number, or a fraction ≥ 0.85) to a matching fractional line with `ANCHOR_TYPE_END`. Media3 ignores bottom padding for spec-defined VTT lines, so this is what actually moves `.vtt` overlay text.

Intentionally high VTT lines (for example `line:10%`) and bitmap cues are left alone. Changing the slider updates the overlay live through `SubtitleConfiguration.verticalPosition`. The live panel's own cue list is not affected.

## Panel open persistence

`PlayerPreferences.liveSubtitlesPanelOpen` (default **false**) remembers whether the user left the live panel open. The cycle, the panel close control, and the rotate button write this pref together with `liveSubtitlesPanelOnLeft`. On activity recreate (pause / screen lock) an open panel restores on the same side when landscape again.

A device or system orientation change that is not the rotate button only hides the panel via the landscape predicate — it does **not** clear `isPanelVisible`. The rotate button does clear it. See **Button cycle and side**.

## Media item / track change (next/prev)

When the media item or selected text-track signature changes, `LiveSubtitlesState` clears cues / highlight / scroll identities, re-enables follow (`isFollowing = true`), and bumps `listResetKey`. The panel keys `LazyListState` on that key so the previous video's offset is dropped; the first cue-list apply (cache hit or progressive seed) then anchors scroll/highlight via `nearestCueIndexByPlayhead` to the new playhead's time zone immediately — even with empty `currentCues` (top only when the playhead is near the start). Progressive fills **within** the same signature keep anti-stutter identity preservation and still re-resolve by playhead while following. Panel open preference is unchanged across videos.

## Auto-follow while paused

Scrolling the cue list unfollows live centering. While **playing**, follow resumes after ~3s (`LiveSubtitlesAutoFollowResumeDelay`). While **paused**, follow never auto-resumes — the list stays where you scrolled. A pending resume is cancelled on pause. When playback **starts** again, follow resumes immediately (YouTube-like catch-up). The jump-to-current button always re-enables follow, including while paused.
