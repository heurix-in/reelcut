# Reelcut

A personal, offline Android video editor in the spirit of InShot, with a dark rounded look and one lemon-yellow accent.

**Phase 1 (this build):** projects list, import videos, full-screen preview, filmstrip timeline (scrub, pinch-zoom, tap to select), trim, split, duplicate, reorder, delete, undo/redo, autosave, and MP4 export (720p/1080p, 30/60 fps) saved to `Movies/Reelcut`, with Play and Share.

**Phase 2:** canvas ratios (9:16, 4:5, 1:1, 16:9) with black or blurred background, photos as clips, speed 0.25×–4×, volume and mute, rotate, mirror, fit/fill.

## Get the APK

Every push to `main` builds on GitHub Actions. The APK appears under **Releases** as `reelcut-0.1.<n>.apk`. All builds share one signing key, so a new APK installs over the old one and keeps your projects.

## Build locally

1. Install Android Studio (JDK 17 + Android SDK 35).
2. Run `./scripts/get-fonts.sh` once (downloads the Urbanist font, SIL Open Font License).
3. `./gradlew assembleDebug` or open the folder in Android Studio.

## Layout

| Path | What |
| --- | --- |
| `data/` | `Project`/`Clip` model, pure `Timeline` maths (unit-tested), JSON `ProjectRepository` |
| `media/` | `PreviewController` (ExoPlayer), `CompositionBuilder` (shared by preview + export), `Exporter` (Transformer), `ThumbnailCache`, `MediaProbe` |
| `editor/` | `EditorViewModel`: edits, undo/redo, autosave, trim/reorder modes, export state |
| `ui/` | Compose screens: Home, Editor (timeline, trim/reorder panels, export sheet), theme |

Library versions are pinned to a known-good set: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Media3 1.5.1.
