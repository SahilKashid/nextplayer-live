# Play while downloading

Next Player Live can play a **local file that is still being written** by an external download
manager (incomplete / growing file), similar to VLC.

## How it works

`GrowingFileDataSource` replaces Media3 `FileDataSource` for `file://` URIs (and for `content://`
URIs that resolve to a readable filesystem path). On `open()` it returns `C.LENGTH_UNSET` so
ExoPlayer does not treat the then-current EOF as the end of the stream. On `read()` it polls the
file (~50ms) until more bytes appear, and only signals end-of-input once size and mtime have been
stable for about thirty seconds, the name does not look like a partial download (`.part`,
`.crdownload`, `.!ut`, `.tmp`, `.download`, `.aria2`, `.bc!`), and nothing has grown within the
recent-growth window.

### ADM / 1DM preallocation (sparse holes **or** real zero padding)

Many advanced download managers **preallocate** the destination (truncate / fallocate) to the
final size and fill it sequentially. `File.length()` then returns the full declared size while
bytes past the download tip are either:

- **sparse holes** (many ADM builds) — detectable via `SEEK_HOLE` on API 26+, or
- **real zero bytes** (1DM-style non-sparse preallocation) — `SEEK_HOLE` returns EOF because the
  zeros are written extents; mtime is often stale while the download writes.

Reading those holes / zero tails as media breaks Matroska parsing
(`IllegalStateException: No valid varint length mask found` → “Can't play video”).

`SparseAwareFileLength` resolves the readable tip as:

1. `Os.lseek(SEEK_HOLE)` when it finds a hole before EOF, else
2. a last-non-zero binary search (`zeroPaddedReadableEnd`, ~64KiB probes) when the path looks like
   a download-manager destination (`1DM`, `/Download/`, `/Downloads/`, `ADM`, `IDM`, `.part`,
   `.crdownload`, …) **or** the last 256KiB of the file is all zeros.

While `readableEnd < declaredLength`, the datasource **blocks/polls** at the tip instead of
returning padding zeros or EOF. As the downloader writes past the tip, the tip advances on each
poll. Download-manager path heuristics also force `FLAG_DISABLE_SEEK_FOR_CUES` and keep load
retries alive even when declared length is large and mtime is old.

Unresolvable `content://` URIs use `GrowingContentDataSource`, which opens via
`ContentResolver.openAssetFileDescriptor` / PFD with the same `LENGTH_UNSET` + reopen-on-EOF
growing semantics, and best-effort SEEK_HOLE on the AFD when available. Media3’s fixed-length
`ContentDataSource` is **not** used for video playback when growing support is desired.

For still-growing (or sparse-preallocated) MKVs, `GrowingAwareExtractorsFactory` sets
`MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES` so playback can start as soon as the header and
early clusters are present, without a long end-cue demux wait. The no-arg `createExtractors()`
also disables cue-seek by default.

`GrowingFileLoadErrorHandlingPolicy` only briefly retries progressive load/source errors
(including `UnrecognizedInputFormatException` / `ParserException`) for local `file://` /
`content://` media while the file is still tiny, actively growing, or looks partial — a hard
cap of a few seconds (~8 × ~600ms), not a long demux delay. That covers empty-at-open races and
rare **MP4/MOV until an early `moov` appears**, instead of failing immediately with
“Source error” / “Can't play video”. Large, stable, non-partial files surface errors quickly.

Network schemes (`smb` / `ftp` / `sftp` / `webdav`) still use `NetworkDataSource`. http(s) still
uses Media3 `DefaultDataSource`.

## Limitations

- Works best with **streamable / growing-friendly containers** (MKV, TS, many incomplete
  progressive downloads).
- **MP4/MOV without an early `moov`** atom may not start until that metadata is present; the load
  retry policy waits while the download grows. (VLC demuxes incomplete MP4s more aggressively.)
- **Duration and seek range** may update only as more media is parsed.
- `content://` is supported via the growing content source when a filesystem path cannot be
  resolved.
- **API 24–25**: sparse `SEEK_HOLE` requires API 26+; zero-tail last-non-zero scanning still
  covers 1DM-style non-sparse preallocation on older devices.

## How to try

1. Start a download of a video (preferably MKV/TS) with any download manager (including ones that
   preallocate the full file size).
2. While the file is still growing, open the partial file in Next Player Live from the file
   picker or a file manager share intent.
3. Playback should start and continue as more bytes are written into the tip; seeking far ahead of
   the downloaded range waits until that offset exists (or errors if the download finishes short).
