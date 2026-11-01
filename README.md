# Reelcut

A personal, offline video editor for **Android and Windows**, in the spirit of InShot, with a dark rounded look and one lemon-yellow accent.

## Get the app

Every push to `main` builds on GitHub Actions and publishes one release with:

- `reelcut-0.2.<n>.apk` – Android. Installs over earlier builds (same signing key) and keeps your projects.
- `Reelcut-Setup-0.2.<n>.exe` – Windows installer (unsigned: choose **More info → Run anyway** the first time).
- `Reelcut-Windows-portable-0.2.<n>.zip` – Windows without installing: unzip and run `Reelcut.exe`.

## Features so far

- Projects list, import videos and photos, full preview
- InShot-style editor: video on top, playback row, labelled toolbar that follows the selection, ruler timeline with + and Mute clip
- Trim, split, duplicate, reorder, delete, undo/redo, autosave
- Canvas 9:16 / 4:5 / 1:1 / 16:9 with black or blurred background; speed 0.25×–4×; volume and mute; rotate, mirror, fit/fill; photo length
- Text (fonts, colours, outline, label, shadow, timing) and logos over the video; any background colour
- MP4 export 720p/1080p, 30/60 fps (Android: Movies/Reelcut; Windows: Videos\Reelcut)

## Project layout (Compose Multiplatform, one module `composeApp`)

| Path | What |
| --- | --- |
| `src/commonMain` | Shared: model, timeline maths (tested), projects, editor ViewModel, all screens |
| `src/androidMain` | Android engine: Media3 preview/export, MediaStore, pickers |
| `src/desktopMain` | Windows engine: FFmpeg (JavaCV) preview, thumbnails, ffmpeg export, file dialog |
| `src/jvmShared` | JDK code compiled into both (project files, ids, dates) |
| `src/commonTest` | Unit tests for timeline and canvas maths |

Build locally: `./scripts/get-fonts.sh`, then `./gradlew :composeApp:assembleDebug` (Android) or `./gradlew :composeApp:run` (desktop).
