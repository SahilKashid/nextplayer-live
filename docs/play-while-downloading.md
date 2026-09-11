# Play while downloading

Next Player Live can play a **local file that is still being written** (incomplete / growing),
regardless of where it is stored on the device.

## How it works

`GrowingFileDataSource` replaces Media3 `FileDataSource` for `file://` URIs (and for `content://`
URIs that resolve to a readable filesystem path). On `open()` it returns `C.LENGTH_UNSET` so
ExoPlayer does not treat the then-current EOF as the end of the stream. On `read()` it polls
(~50ms) until more bytes appear.

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
4. Optional: declared / readable length grew across a short poll

`IncompleteLocalMedia` is the single helper used by the growing datasources, extractors factory,
and load-error policy. A zero-preallocated incomplete MKV under `/Movies/` behaves the same as
one under any other directory. A finished MKV whose tail has real cues / non-zero data is **not**
incomplete.

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

## Incomplete MKV seeking (estimated)

With cue-EOF seek disabled, Media3 normally emits an unseekable `SeekMap`, so scrubbing does
nothing. Next Player Live wraps incomplete Matroska extractors with
`IncompleteMatroskaSeekExtractor`:

1. `GrowingFileDataSource` / `GrowingContentDataSource` publish the sparse/zero-tail aware tip
   via `ReadableTipTracker` (under path, URI, and `file://` key forms).
2. When the delegate reports an unseekable map that still has a known duration, it is replaced
   with `ApproximateByteSeekMap` (full Info duration on the timeline; **tip-relative** byte
   mapping across the downloaded tip, with a ~1 MiB safety margin so scrubbing never lands in
   the unfinished / zero-padded edge). Mid-timeline scrub stays mid-tip — it does **not** clamp
   to tip−1 when declared length ≫ tip.
3. On seek, the byte position is clamped to the safe tip and snapped back to the nearest
   **validated** preceding Cluster (`1F 43 B6 75` + plausible EBML size varint) within a 4 MiB
   scan. Candidates near the tip edge or with a zero size byte are rejected; if none match,
   seek falls back to position `0`.

This is **estimated** seeking across the downloaded tip (VLC-like). Avoid seeking into the
unfinished edge — that region is often a truncated cluster or zero padding and will fail parse.
It may be less accurate than VLC until real cues exist at EOF; finished files keep normal
cue-based seeking and are not wrapped.

## Limitations

- Works best with **streamable / growing-friendly containers** (MKV, TS, many incomplete
  progressive downloads).
- **MP4/MOV without an early `moov`** may not start until that metadata is present; the load
  retry policy waits briefly while the download grows.
- **Duration and seek range** may update only as more media is parsed.
- **Incomplete MKV scrubbing** is estimated within the downloaded tip and may be less accurate
  than VLC until cues exist.
- **API 24–25**: `SEEK_HOLE` requires API 26+; zero-tail last-non-zero scanning still covers
  non-sparse preallocation.

## How to try

1. Start a download of a video (preferably MKV/TS) with any download manager.
2. While the file is still growing — stored anywhere — open it in Next Player Live.
3. Playback should start and continue as more bytes are written into the tip; scrubbing within
   the timeline seeks approximately across the downloaded tip (estimated, safety-margined), and
   snaps to a validated Cluster.
