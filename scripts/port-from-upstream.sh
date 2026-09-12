#!/usr/bin/env bash
# Scaffold a Next Player Live port onto a newer upstream Next Player ref.
# Safe: creates a branch, prints checklists, does not force-push.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

UPSTREAM_URL="https://github.com/anilbeesetti/nextplayer.git"
ORIGIN_URL="https://github.com/SahilKashid/nextplayer-live.git"

usage() {
  cat <<'USAGE'
Usage: ./scripts/port-from-upstream.sh [upstream-ref] [live-source-ref]

  upstream-ref     Tag or branch on upstream (default: prompt, or upstream/main)
                   Examples: v0.18.0  upstream/v0.18.0  upstream/main
  live-source-ref  Live tree to copy features from (default: v1.0.1 if tagged,
                   else latest Live tag, else origin/main)

Creates branch live/port-<sanitized-ref> from the upstream ref, ensures remotes,
fetches tags, prints branding + copy paths + touchpoints + next commands.

Does NOT force-push. Does NOT auto-merge touchpoint files.

See docs/porting-from-upstream.md
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ensure_remote() {
  local name="$1" url="$2"
  if git remote get-url "$name" >/dev/null 2>&1; then
    echo "Remote '$name' OK: $(git remote get-url "$name")"
  else
    echo "Adding remote '$name' -> $url"
    git remote add "$name" "$url"
  fi
}

sanitize_ref() {
  # v0.18.0 / upstream/main -> v0-18-0 / main
  local r="$1"
  r="${r#upstream/}"
  r="${r#origin/}"
  r="${r#refs/tags/}"
  r="${r#refs/heads/}"
  echo "$r" | tr '/.' '-' | tr -cd 'A-Za-z0-9._-' | sed 's/^-*//;s/-*$//'
}

resolve_upstream_ref() {
  local raw="$1"
  if git rev-parse --verify -q "$raw" >/dev/null 2>&1; then
    echo "$raw"
    return
  fi
  if git rev-parse --verify -q "upstream/$raw" >/dev/null 2>&1; then
    echo "upstream/$raw"
    return
  fi
  if git rev-parse --verify -q "refs/tags/$raw" >/dev/null 2>&1; then
    echo "$raw"
    return
  fi
  echo "ERROR: cannot resolve upstream ref: $raw" >&2
  echo "Tried: $raw, upstream/$raw" >&2
  exit 1
}

resolve_live_ref() {
  local raw="$1"
  if git rev-parse --verify -q "$raw" >/dev/null 2>&1; then
    echo "$raw"
    return
  fi
  if git rev-parse --verify -q "origin/$raw" >/dev/null 2>&1; then
    echo "origin/$raw"
    return
  fi
  if git rev-parse --verify -q "refs/tags/$raw" >/dev/null 2>&1; then
    echo "$raw"
    return
  fi
  echo "ERROR: cannot resolve Live source ref: $raw" >&2
  exit 1
}

# --- remotes ---
ensure_remote upstream "$UPSTREAM_URL"
ensure_remote origin "$ORIGIN_URL"

echo ""
echo "==> Fetching upstream + origin (with tags)..."
git fetch upstream --tags
git fetch origin --tags

# --- args ---
UPSTREAM_ARG="${1:-}"
LIVE_ARG="${2:-}"

if [[ -z "$UPSTREAM_ARG" ]]; then
  if [[ -t 0 ]]; then
    read -r -p "Upstream ref [upstream/main]: " UPSTREAM_ARG
  fi
  UPSTREAM_ARG="${UPSTREAM_ARG:-upstream/main}"
fi

if [[ -z "$LIVE_ARG" ]]; then
  # Prefer v1.0.1 (finished-download handoff), else newest v* tag, else origin/main.
  if git rev-parse --verify -q "v1.0.1" >/dev/null 2>&1 || \
     git rev-parse --verify -q "refs/tags/v1.0.1" >/dev/null 2>&1; then
    LIVE_ARG="v1.0.1"
  else
    latest="$(git tag -l 'v*' --sort=-v:refname 2>/dev/null | head -n1 || true)"
    if [[ -n "${latest:-}" ]]; then
      LIVE_ARG="$latest"
    else
      LIVE_ARG="origin/main"
    fi
  fi
fi

UPSTREAM_REF="$(resolve_upstream_ref "$UPSTREAM_ARG")"
LIVE_REF="$(resolve_live_ref "$LIVE_ARG")"
BRANCH="live/port-$(sanitize_ref "$UPSTREAM_REF")"

echo ""
echo "Upstream ref : $UPSTREAM_REF ($(git rev-parse --short "$UPSTREAM_REF"))"
echo "Live source  : $LIVE_REF ($(git rev-parse --short "$LIVE_REF"))"
echo "New branch   : $BRANCH"

if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  echo ""
  echo "Branch '$BRANCH' already exists."
  echo "  Checkout:  git checkout $BRANCH"
  echo "  Or delete: git branch -D $BRANCH  then re-run this script"
  echo "Not switching or resetting (idempotent / safe)."
else
  echo ""
  echo "==> Creating branch $BRANCH from $UPSTREAM_REF"
  git checkout -b "$BRANCH" "$UPSTREAM_REF"
fi

# --- Live-only paths (safe to checkout from Live ref) ---
LIVE_ONLY_PATHS=(
  # Feature A — Live subtitles
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/ui/LiveSubtitlesPanel.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/state/LiveSubtitlesState.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/LiveSubtitleCueCache.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/SubtitleCueLoader.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/SubtitleCueParser.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/EmbeddedSubtitleCueExtractor.kt"
  "core/ui/src/main/res/drawable/ic_live_subtitles.xml"
  # Feature B — growing datasources + factories
  "core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/GrowingFileDataSource.kt"
  "core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/GrowingContentDataSource.kt"
  "core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/IncompleteLocalMedia.kt"
  "core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/SparseAwareFileLength.kt"
  "core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/ReadableTipTracker.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/GrowingAwareExtractorsFactory.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/GrowingFileLoadErrorHandlingPolicy.kt"
  # Feature C — incomplete MKV seek
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/EbmlProbe.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/MatroskaClusterIndexer.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/IncompleteMatroskaSeekExtractor.kt"
  # Docs
  "docs/live-subtitles.md"
  "docs/play-while-downloading.md"
  "docs/porting-from-upstream.md"
  "scripts/port-from-upstream.sh"
  # Tests
  "core/media/src/test/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/GrowingFileDataSourceTest.kt"
  "core/media/src/test/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/ReadableTipTrackerTest.kt"
  "feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/service/EbmlProbeTest.kt"
  "feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/service/MatroskaClusterIndexerTest.kt"
  "feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/service/IncompleteMatroskaSeekExtractorTest.kt"
  "feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/LiveSubtitleCueCacheTest.kt"
  "feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/EmbeddedSubtitleCueExtractorTest.kt"
  "feature/player/src/test/java/dev/anilbeesetti/nextplayer/feature/player/utils/subtitle/SubtitleCueParserTest.kt"
)

TOUCHPOINTS=(
  "app/build.gradle.kts"
  "core/ui/src/main/res/values/strings.xml"
  "core/media/src/main/java/dev/anilbeesetti/nextplayer/core/media/network/datasource/NextDataSourceFactory.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/service/PlayerService.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/MediaPlayerScreen.kt"
  "feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/ui/controls/ControlsTopView.kt"
  "README.md"
)

echo ""
echo "========================================"
echo " BRANDING CHECKLIST (must keep)"
echo "========================================"
cat <<'BRAND'
  [ ] app/build.gradle.kts: applicationId = "dev.sahilkashid.nextplayer"
  [ ] namespace may stay "dev.anilbeesetti.nextplayer"
  [ ] core/ui/.../strings.xml: app_name = "Next Player Live"
  [ ] Permission / player activity strings say Next Player Live
  [ ] versionName / versionCode are Live's line (v1.x / 10x), not upstream's
BRAND

echo ""
echo "========================================"
echo " COPY Live-only paths from $LIVE_REF"
echo "========================================"
echo "Review the list, then run (skips missing paths on Live ref):"
echo ""
echo "  LIVE_REF=$LIVE_REF"
echo "  paths=("
for p in "${LIVE_ONLY_PATHS[@]}"; do
  echo "    $p"
done
echo "  )"
echo "  existing=()"
echo "  for p in \"\${paths[@]}\"; do"
echo "    if git cat-file -e \"\$LIVE_REF:\$p\" 2>/dev/null; then existing+=(\"\$p\"); fi"
echo "  done"
echo "  git checkout \"\$LIVE_REF\" -- \"\${existing[@]}\""
echo ""

# Offer to run copy if interactive and on the new branch
if [[ -t 0 ]] && [[ "$(git branch --show-current 2>/dev/null || true)" == "$BRANCH" ]]; then
  read -r -p "Copy Live-only paths from $LIVE_REF now? [y/N] " ans || true
  if [[ "${ans:-}" =~ ^[Yy]$ ]]; then
    existing=()
    for p in "${LIVE_ONLY_PATHS[@]}"; do
      if git cat-file -e "$LIVE_REF:$p" 2>/dev/null; then
        existing+=("$p")
      else
        echo "  (skip missing on Live ref) $p"
      fi
    done
    if ((${#existing[@]})); then
      git checkout "$LIVE_REF" -- "${existing[@]}"
      echo "Checked out ${#existing[@]} path(s) from $LIVE_REF (staged)."
    else
      echo "No Live-only paths found on $LIVE_REF."
    fi
  else
    echo "Skipped copy. Use the command block above when ready."
  fi
fi

echo ""
echo "========================================"
echo " TOUCHPOINTS — merge manually (do NOT blind-overwrite)"
echo "========================================"
for p in "${TOUCHPOINTS[@]}"; do
  echo "  - $p"
done
cat <<'TOUCH'
  MediaPlayerScreen: rememberLiveSubtitlesState, ~0.65/0.35 landscape, hide overlay when panel open
  ControlsTopView: onLiveSubtitlesClick / isLiveSubtitlesVisible / showLiveSubtitlesToggle
  NextDataSourceFactory: Growing* ONLY while shouldPlayAsGrowing; settled-complete -> DefaultDataSource
  Finished-download handoff: IncompleteLocalMedia / SparseAwareFileLength / Growing* /
                             GrowingAwareExtractorsFactory — DefaultDataSource when settled-complete
                             (never always-Growing; blank loader pitfall fixed in v1.0.1 / 11c919b2)
  PlayerService: DefaultMediaSourceFactory(context, GrowingAwareExtractorsFactory) + setDataSourceFactory
                 + GrowingFileLoadErrorHandlingPolicy  (Media3 1.11: extractors via constructor)
  strings.xml: live_subtitles* + jump_to_current_cue (+ app_name)
TOUCH

echo ""
echo "========================================"
echo " DO NOT PORT"
echo "========================================"
cat <<'NOPORT'
  - PlaybackFailure Copy-log diagnostics
  - DownloadPathHeuristic / folder-name special cases
  - ApproximateByteSeekMap / MatroskaClusterFinder
  - Always-Growing routing for settled-complete / finished files
NOPORT

echo ""
echo "========================================"
echo " NEXT COMMANDS"
echo "========================================"
cat <<'NEXT'
  # Tests
  ./gradlew :core:media:testDebugUnitTest :feature:player:testDebugUnitTest

  # Build (prefer release-with-debug-signing if available)
  ./gradlew assembleRelease-with-debug-signing || ./gradlew assembleDebug

  # After branding + bump Live versionName/versionCode:
  git add -A
  git status
  # commit, push origin HEAD, tag v1.x, gh release create ...

  # Avoid committing .github/workflows unless OAuth has workflow scope

  # Full guide: docs/porting-from-upstream.md
NEXT

echo ""
echo "Done. Branch: $BRANCH  |  No force-push performed."
