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

## Limitations

- Works best with **streamable / growing-friendly containers** (MKV, TS, many incomplete
  progressive downloads).
- **MP4/MOV without an early `moov`** may not start until that metadata is present; the load
  retry policy waits briefly while the download grows.
- **Duration and seek range** may update only as more media is parsed.
- **API 24–25**: `SEEK_HOLE` requires API 26+; zero-tail last-non-zero scanning still covers
  non-sparse preallocation.

## How to try

1. Start a download of a video (preferably MKV/TS) with any download manager.
2. While the file is still growing — stored anywhere — open it in Next Player Live.
3. Playback should start and continue as more bytes are written into the tip; seeking far ahead of
   the downloaded range waits until that offset exists (or errors if the download finishes short).
