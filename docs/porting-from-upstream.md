# Porting Next Player Live onto newer upstream Next Player

**Audience:** AI agents and humans rebasing Live features onto a newer
[`anilbeesetti/nextplayer`](https://github.com/anilbeesetti/nextplayer) release.

**Helper:** [`scripts/port-from-upstream.sh`](../scripts/port-from-upstream.sh)

Related deep-dives (keep in sync when behavior changes):

- [`docs/live-subtitles.md`](live-subtitles.md)
- [`docs/play-while-downloading.md`](play-while-downloading.md)

---

## Purpose

**Next Player Live** is a fork of Next Player with:

1. **Live subtitles panel** — landscape side panel listing cues for the selected text track
2. **Play while downloading** — play local files that are still being written
3. **Seek on incomplete MKVs** — EBML cluster index so scrubbing works before cues exist at EOF
4. **Rebrand** — distinct app identity so it can sit beside stock Next Player

Side-by-side install is intentional: Live uses a different `applicationId` so it does **not**
overwrite the original Next Player package.

Baseline: Live **v1.0.0** was based around the upstream **~v0.17.5** lineage. Origin tag
`v1.0.0` is the first Live release; **v1.0.1** adds finished-download settled-complete handoff;
**v1.0.2** adds the same for **Open with / share-sheet `content://` URIs**;
**v1.0.3** ships live-subtitles false-unsupported + VTT panel fix + overlay-with-panel + vertical position;
**v1.0.4** adds panel persistence across pause/lock/recreate and no auto-follow while paused;
**v1.0.5** adds expanding-window progressive cue fill + hardened final-only cue cache
(prefer `v1.0.5` or latest Live tag / `origin/main`).

---

## Remotes

| Remote   | URL                                                        |
|----------|------------------------------------------------------------|
| upstream | `https://github.com/anilbeesetti/nextplayer.git`           |
| origin   | `https://github.com/SahilKashid/nextplayer-live.git`       |

```bash
git remote add upstream https://github.com/anilbeesetti/nextplayer.git   # if missing
git remote add origin https://github.com/SahilKashid/nextplayer-live.git # if missing
git fetch upstream --tags
git fetch origin --tags
```

---

## Recommended workflow

1. `git fetch upstream --tags` (and `git fetch origin --tags`).
2. Checkout a **new** branch from the target upstream ref:
   - Prefer a release tag: `upstream/vX.Y.Z`
   - Or tip: `upstream/main`
3. Re-apply **branding** (see [Branding](#branding-must-keep)).
4. Bring Live-only source trees from origin tag `v1.0.5` (or latest Live tag / `main`), then
   **re-wire touchpoints** in shared upstream files.
5. Prefer history when clean:
   - Cherry-pick / merge Live feature commits if they apply cleanly.
   - Else: `git checkout <live-ref> -- <paths>` for Live-only files + **manual merge** of
     touchpoint files (do not blind-overwrite upstream player UI / service files).
6. Run unit tests for growing/media + player seek/subtitle suites (see [Tests](#tests-to-port)).
7. Build: `assembleRelease-with-debug-signing` (or debug). Bump Live `versionName` /
   `versionCode` **independently** of upstream.
8. Push to origin, create GitHub Release `v1.x` with the universal APK.
9. Shortcut for steps 1–5 scaffolding: `./scripts/port-from-upstream.sh <upstream-ref> [live-ref]`

Do **not** force-push `main` unless explicitly requested. Prefer a branch like
`live/port-v0-18-0`, validate, then PR or fast-forward merge.

---

## Branding (must keep)

| Location | Rule |
|----------|------|
| `app/build.gradle.kts` | `applicationId = "dev.sahilkashid.nextplayer"` |
| `app/build.gradle.kts` | `namespace` **may** stay `dev.anilbeesetti.nextplayer` |
| `core/ui/.../strings.xml` | `app_name` = `Next Player Live` |
| Same strings / manifests | Permission / player activity labels that say **Next Player Live** |
| Version line | Live’s own: `v1.0.0` / `100`, `v1.0.1` / `101`, `v1.0.2` / `102`, `v1.0.3` / `103`, `v1.0.4` / `104`, `v1.0.5` / `105`, … — **not** upstream’s |

If upstream bumped versions in `app/build.gradle.kts`, keep Live’s numbers (or continue the Live
sequence). Never publish Live under upstream’s `applicationId`.

---

## Feature A — Live subtitles panel

### Files to carry (copy from Live tree)

```
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/ui/LiveSubtitlesPanel.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/state/LiveSubtitlesState.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/state/LiveSubtitlesFollowController.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/LiveSubtitleCueCache.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/SubtitleCueLoader.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/SubtitleCueParser.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/EmbeddedSubtitleCueExtractor.kt
core/ui/src/main/res/drawable/ic_live_subtitles.xml
```

**Strings** (in `core/ui/src/main/res/values/strings.xml` and locales as needed):

- `live_subtitles`
- `show_live_subtitles` / `hide_live_subtitles`
- `jump_to_current_cue`
- `live_subtitles_empty`
- `live_subtitles_unsupported`

Also keep `docs/live-subtitles.md` when present.

### Wire into (manual merge)

| File | What to restore |
|------|-----------------|
| `feature/player/.../MediaPlayerScreen.kt` | `rememberLiveSubtitlesState`; landscape weight split ~**0.65 / 0.35**; overlay visibility follows `showOverlaySubtitlesWithLivePanel` (default both on); host `LiveSubtitlesPanel`; pass toggle props |
| `feature/player/.../ui/controls/ControlsTopView.kt` | `onLiveSubtitlesClick` / `isLiveSubtitlesVisible` / `showLiveSubtitlesToggle` |

### Hard-won behavior (do not “simplify” away)

- **Threading:** `MediaController` / player APIs **only on Main**; demux and file I/O on
  `Dispatchers.IO`.
- **Highlight:** primary from `EVENT_CUES` / `currentCues`; ~**50ms** fallback tick while panel
  open; **early-lead (~380ms adaptive)** for both scroll **and** highlight via `scrollTargetKey`;
  sticky-forward lead; **stable cue identity keys**; **snap colors** (no flicker animations on
  rapid cue changes).
- **Load path:** expanding-window progressive embedded demux (seed near playhead, grow earlier/later) + hardened final-only disk/memory cue cache (stable keys, no flaky mtime) (v1.0.5).
- **UX:** no top “Live subtitles” header; **center** the active cue; image subs (PGS / VobSub /
  DVB) unsupported → empty unsupported state. Empty **text** tracks must **not** show the
  image-based unsupported string (v1.0.3).
- **Empty cache:** never cache empty cue lists; ASS/SSA may need a raw-sample retry demux (v1.0.3).
- **WebVTT panel:** resolve sideloaded `.vtt` via MEDIA3_CUES / codecs → external file (v1.0.3).
- **Overlay + panel:** `showOverlaySubtitlesWithLivePanel` default **on** (v1.0.3).
- **Vertical position:** overlay slider + bottom-anchored VTT lift (v1.0.3).
- **Panel persist:** `liveSubtitlesPanelOpen` across pause / lock / recreate; portrait hides only via predicate (v1.0.4).
- **Pause auto-follow:** cue scroll auto-follow resumes only while playing; jump-to-current always works (v1.0.4).
- **Expanding-window fill:** seed near playhead then grow earlier/later bands with frequent partials (v1.0.5).
- **Hardened cue cache:** final-only put, stable keys (no flaky mtime), larger LRU, fsync+rename, corrupt delete (v1.0.5).

See `docs/live-subtitles.md`.

---

## Feature B — Play while downloading

### Files to carry

```
core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/GrowingFileDataSource.kt
core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/GrowingContentDataSource.kt
core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/IncompleteLocalMedia.kt
core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/SparseAwareFileLength.kt
core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/ReadableTipTracker.kt
```

### Modify / wire (manual merge)

| File | What to restore |
|------|-----------------|
| `.../datasource/NextDataSourceFactory.kt` | Route local `file` / resolvable `content` → Growing* **only** while `shouldPlayAsGrowing`; **settled-complete → DefaultDataSource**; keep network / http paths |
| `feature/player/.../service/GrowingAwareExtractorsFactory.kt` | Live-only — copy whole file |
| `feature/player/.../service/GrowingFileLoadErrorHandlingPolicy.kt` | Live-only — copy whole file |
| `feature/player/.../service/PlayerService.kt` | `DefaultMediaSourceFactory(context, GrowingAwareExtractorsFactory)` + `setDataSourceFactory` + `GrowingFileLoadErrorHandlingPolicy` |

**Media3 1.11 note:** extractors go through the **`DefaultMediaSourceFactory` constructor**, not
`setExtractorsFactory`.

Also keep `docs/play-while-downloading.md` when present.

### Hard-won pitfalls

- Incomplete detection is **content-based only** (partial suffix / `SEEK_HOLE` / zero-tail
  last-non-zero / short growth poll). **NEVER** special-case folder names like `1DM` / `Download`.
- Preallocated downloads: `File.length()` **lies**. You need `SEEK_HOLE` +
  `zeroPaddedReadableEnd` or Matroska reads zeros → `No valid varint length mask`.
- Incomplete MKV: set `FLAG_DISABLE_SEEK_FOR_CUES` or Media3 seeks EOF cues and fails
  ([ExoPlayer#8935](https://github.com/google/ExoPlayer/issues/8935)).
- Do **not** declare EOF on short write pauses (~**30s** stable tip before treating as complete
  for retry/EOF policy — follow Live code).
- Far seeks past tip must **fail fast**.
- **Settled-complete handoff (must port):** `IncompleteLocalMedia.shouldPlayAsGrowing`,
  `SparseAwareFileLength` (ignore stale far `SEEK_HOLE` once tail has real bytes),
  `NextDataSourceFactory` (DefaultDataSource when settled-complete), Growing* open/EOF behavior,
  and `GrowingAwareExtractorsFactory` (wrap / disable cue-seek **only** while incomplete).
  Always routing finished files through Growing* (`LENGTH_UNSET`) + leftover `SEEK_HOLE` ⇒
  **blank loading screen forever**. Fixed at `11c919b2` (**v1.0.1**) for path-resolvable URIs;
  Open-with / share-sheet `content://` finished files fixed at `0e968022` (**v1.0.2**) via AFD inspection.
  Do **not** reintroduce always-Growing for finished files or path heuristics.

See `docs/play-while-downloading.md`.

---

## Feature C — Seek on incomplete MKVs

### Files to carry

```
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/EbmlProbe.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/MatroskaClusterIndexer.kt
feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/IncompleteMatroskaSeekExtractor.kt
```

`GrowingAwareExtractorsFactory` wraps Matroska with `IncompleteMatroskaSeekExtractor` when the
local file is incomplete.

### Hard-won pitfalls

- **`ApproximateByteSeekMap` + byte-scan for `1F43B675` failed** (Invalid integer size / varint).
  **DO NOT reintroduce** that approach or `MatroskaClusterFinder`.
- Use **EBML-validated Cluster + Timecode → `IndexSeekMap`**; require **≥ 2** clusters; keep a
  **safety margin** before tip; **rebuild** when tip grows **> 8 MiB**.
- Tip tracker keys: both path and `file://` forms (and content keys as implemented in Live).

---

## Tests to port

Copy (or restore) at least:

```
core/media/src/test/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/GrowingFileDataSourceTest.kt
core/media/src/test/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/ReadableTipTrackerTest.kt
feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/service/EbmlProbeTest.kt
feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/service/MatroskaClusterIndexerTest.kt
feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/service/IncompleteMatroskaSeekExtractorTest.kt
feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/LiveSubtitleCueCacheTest.kt
```

Also port if present on the Live ref:

- `IncompleteLocalMedia` / `SparseAware*` tests
- `EmbeddedSubtitleCueExtractorTest` / `SubtitleCueParserTest`

Suggested run:

```bash
./gradlew :core:media:testDebugUnitTest :feature:player:testDebugUnitTest
```

---

## Release checklist

1. Bump Live `versionCode` / `versionName` in `app/build.gradle.kts` (Live line, not upstream).
2. Build universal APK — prefer `assembleRelease-with-debug-signing` (or project’s equivalent).
3. Commit; tag `vX.Y.Z`; `gh release create` with the APK attached.
4. **Avoid committing `.github/workflows` changes** if the GitHub OAuth token lacks the
   `workflow` scope (known push failure). Leave workflow edits for a token that has that scope.

---

## Do NOT port

| Item | Why |
|------|-----|
| Temporary PlaybackFailure “Copy log” diagnostics | Removed on purpose |
| `DownloadPathHeuristic` / path / folder-name special cases | Incomplete detection must stay content-based |
| `ApproximateByteSeekMap` / `MatroskaClusterFinder` | Failed approach; causes varint / invalid integer size errors |

---

## Verification matrix (short)

| Check | Expect |
|-------|--------|
| Finished MKV seek | Works (cue-based; not wrapped as incomplete) |
| After download completes, open file | Plays immediately (not blank loading screen); DefaultDataSource path |
| Mid-download MKV open | Starts without waiting for full file |
| Mid-download scrub | Seeks to indexed downloaded clusters |
| Live subtitles panel | Landscape split; highlight/scroll sync; overlay stays on by default (`showOverlaySubtitlesWithLivePanel`) |
| Open with / share sheet | Finished local video via ACTION_VIEW / `content://` plays (not blank loader) |
| Side-by-side install | Original Next Player package **not** overwritten (`dev.sahilkashid.nextplayer`) |

---

## Agent-efficient checklist

When porting, work in this order:

1. Remotes + fetch + branch from upstream ref (`./scripts/port-from-upstream.sh` helps).
2. Branding (`applicationId`, `app_name`, Live version).
3. Checkout Live-only paths from `v1.0.5` / latest Live tag / Live main.
4. Manually merge touchpoints: `NextDataSourceFactory`, `PlayerService`, `MediaPlayerScreen`,
   `ControlsTopView`, strings.
5. Tests → assemble → smoke verification matrix → release.

If something “looks simpler” but contradicts a **Hard-won** bullet above, keep Live behavior.
