# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install on connected device/emulator
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug

# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

**SDK targets:** minSdk 24, targetSdk 35, compileSdk 36, Kotlin 2.0.21, JVM 11.

## Architecture

Single-module Android app (`com.frandroidlabs.playthenews`) using Views (not Compose) with coroutines for async work.

### Key Components

**MainActivity** — Main screen. Loads an OPML file, fetches the latest episode from each podcast RSS feed (concurrent, capped at 6 via `Semaphore(6)`), builds a playlist of `Track` objects, and hands them to ExoPlayer as `MediaItem`s. Polls every 1s via `Handler` to update progress bars and highlight the current track.

**PlaybackService** — `MediaSessionService` foreground service that owns the `ExoPlayer` instance, enabling background playback. Also saves playback position every 1s independently of the Activity.

**PositionStore** — Singleton `object` wrapping `SharedPreferences` (key `"prefs"`). Persists position per track URL (`"pos:{url}"`) and the last active URL. Both MainActivity and PlaybackService write here; the Activity reads on startup to restore position.

**PlaylistAdapter / PlaylistItemTouchHelper** — RecyclerView adapter with drag-to-reorder and swipe-to-delete (edit mode only). Progress values (0–1000 scale) are stored in a `progressMap` keyed by URL so they survive list reorders.

**SearchActivity** — Queries the iTunes Search API for podcasts, shows results with checkboxes, then appends selected feeds as OPML `<outline>` entries to SharedPreferences before returning `RESULT_OK` to MainActivity.

### Data Flow

1. **Startup:** `onStart()` connects a `MediaController` to PlaybackService → `updateList()` parses OPML → concurrent RSS fetch → `setPlaylist()` loads ExoPlayer → position restored from `PositionStore`.
2. **OPML persistence:** Stored in SharedPreferences as XML string. Reconstructed from the current playlist list on every edit (reorder/delete) or podcast add.
3. **Position persistence:** Written by both Activity (poll) and Service (poll + on pause). A `positionAlreadyRestored` flag prevents double-restoration during `onMediaItemTransition`.

### XML Parsing

OPML and RSS are parsed with Android's built-in `XmlPullParser`. RSS parsing is namespace-aware to handle iTunes and Media namespace extensions for artwork and episode metadata.

### Key Patterns

- UI operations: `Dispatchers.Main` or `runOnUiThread`; network/IO: `Dispatchers.IO`
- RecyclerView animations disabled (`SimpleItemAnimator.supportsChangeAnimations = false`) to prevent flashing during 1s progress updates
- Glide and Picasso are both present (Glide in deps, Picasso used in code — do not add a third image library)
