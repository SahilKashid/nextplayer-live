# Play while downloading

Next Player Live can play a **local file that is still being written** (incomplete / growing),
regardless of where it is stored on the device.

## How it works

`GrowingFileDataSource` replaces Media3 `FileDataSource` for `file://` URIs (and for `content://`
URIs that resolve to a readable filesystem path) **while the file still looks incomplete**.
On `open()` it returns `C.LENGTH_UNSET` so ExoPlayer does not treat the then-current EOF as
the end of the stream. On `read()` it polls (~50ms) until more bytes appear.

Once the file is **settled-complete** — not a partial name, readable tip has caught the
declared length (or the last ~256KiB already has real bytes and any `SEEK_HOLE` is far from
EOF), and mtime is stable or a short poll sees no further growth — `NextDataSourceFactory`
routes it to Media3 `DefaultDataSource`. Playback then uses a finite length, normal Matroska
cue-seek, and no incomplete-MKV wrapper.

Unresolvable `content://` URIs use `GrowingContentDataSource` (AFD / PFD, same `LENGTH_UNSET` +
reopen-on-EOF semantics). Media3’s fixed-length `ContentDataSource` is not used for this path.

Network schemes (`smb` / `ftp` / `sftp` / `webdav`) still use `NetworkDataSource`. http(s) still
uses Media3 `DefaultDataSource`.

## Universal incomplete detection

A local file is treated as incomplete when **any** of these content signals is true — never
because of a folder name:

1. Partial filename suffix (`.part`, `.crdownload`, `.!ut`, `.tmp`, `.download`, `.aria2`, `.bc!`)
2. `SEEK_HOLE` readable end is behind the declared `File.length()`
3. The last ~256KiB is all zeros, and the last-non-zero tip (`zeroPaddedReadableEnd`) is behind
   the declared length
4. Optional: declared / readable length grew across a short poll (only if mtime is recent)

`IncompleteLocalMedia` is the single helper used by the growing datasources, extractors factory,
and load-error policy. A zero-preallocated incomplete MKV under `/Movies/` behaves the same as
one under any other directory.

A finished file is **not** incomplete when the tip has caught the declared length, or when the
last ~256KiB already contains real cues / media (a leftover `SEEK_HOLE` far from EOF is ignored).
`shouldPlayAsGrowing` is false for those files once mtime is stable (or a 250ms poll sees no
growth), so they take the finite-length path.

## Sparse holes and zero-filled tails

Downloaders may preallocate the destination at the final size and fill it sequentially.
`File.length()` then returns the full declared size while bytes past the download tip are either
sparse holes (`SEEK_HOLE` on API 26+) or real zero bytes. `SparseAwareFileLength` takes
`readableEnd = min(holeTip, zeroTailTipIfApplicable)` and the datasource polls at that tip —
never returning padding zeros as media.

## Extractors and retries

For still-incomplete local MKVs, `GrowingAwareExtractorsFactory` sets
`MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES` so playback can start from the header and early
clusters. Finished local MKVs keep cue-seek enabled so seeking is preserved. The no-arg
`createExtractors()` disables cue-seek (unknown URI). Network URIs keep cue-seek enabled.

`GrowingFileLoadErrorHandlingPolicy` briefly retries progressive load/source errors (including
parse / varint races into a zero tail) while the file still looks incomplete or the tip is
advancing. A small cap applies when the tip is stuck. Complete files fail fast.

Far cue-style seeks past the current tip fail fast so load can proceed without waiting for
end-of-file cues.

## Incomplete MKV seeking (cluster index)

With cue-EOF seek disabled, Media3 emits an unseekable `SeekMap`. For incomplete local Matroska,
`GrowingAwareExtractorsFactory` wraps the extractor with `IncompleteMatroskaSeekExtractor`:

1. Growing datasources publish the sparse/zero-tail tip via `ReadableTipTracker`.
2. `MatroskaClusterIndexer` walks EBML from the Segment start up to `safeTip` (tip minus ~1 MiB)
   and records Clusters that have a valid size and a Timecode in the first ~64 bytes of payload.
   Two or more validated Clusters become an `IndexSeekMap` (full Info duration; seek points at
   Cluster starts). Fewer than two → stay `Unseekable`.
3. On seek, the byte position is clamped to the safe tip and snapped to the nearest indexed
   Cluster at or before the target. The index rebuilds when the tip grows by more than 8 MiB.

Scrubbing only hits **already-downloaded, indexed Clusters**. Finished files keep cue-based
seeking and are not wrapped.

## Limitations

- Works best with **streamable / growing-friendly containers** (MKV, TS, many incomplete
  progressive downloads).
- **MP4/MOV without an early `moov`** may not start until that metadata is present; the load
  retry policy waits briefly while the download grows.
- **Duration and seek range** may update only as more media is parsed.
- **Incomplete MKV scrubbing** only hits indexed downloaded Clusters; the duration bar still
  shows the full length. Accuracy is Cluster-level until real cues exist at EOF.
- **API 24–25**: `SEEK_HOLE` requires API 26+; zero-tail last-non-zero scanning still covers
  non-sparse preallocation.

## How to try

1. Start a download of a video (preferably MKV/TS) with any download manager.
2. While the file is still growing — stored anywhere — open it in Next Player Live.
3. Playback should start and continue as more bytes are written into the tip; scrubbing seeks
   to validated downloaded Clusters (safety-margined). The duration bar shows the full length.
