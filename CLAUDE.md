# Reelcut – Android video editor

Spec doc (phases, design, acceptance checklist): https://claude.ai/artifact/M2mWUvGDY9BkSDaFDuJx4c
Progress and known issues: PROGRESS.md.

## Rules
- One shared app for Android + Windows: Kotlin, Compose Multiplatform, Material 3, MVVM, coroutines/StateFlow.
  Shared code in composeApp/src/commonMain; platform code only behind `engine/Engine.kt` and `Platform.kt` expects.
- Android engine: Media3 (ExoPlayer preview, Transformer export). Windows engine: FFmpeg via JavaCV
  (preview/thumbnails) and the bundled ffmpeg program (export). Both must frame video the same way (`CanvasMath`).
- `Project` (JSON) is the single source of truth; services come from `Services.current`.
- All times in microseconds (Long). Timeline maths lives in `data/Timeline.kt` with unit tests; keep it pure.
- Never block the main thread with decoding or file IO.
- Colours, type and shapes live in `ui/theme/Theme.kt`; don't hard-code them in screens.
  Look: warm near-black (#1B1915), one gold accent (#D4AF5A, always a fill with dark content), quiet glass surfaces
  (dark scrim + light gradient + hairline edge: `Modifier.glass()`, `appBackground()` in ui/theme/Glass.kt), round buttons, pills.
- Do not change the Project schema without keeping old JSON readable (`ignoreUnknownKeys`, defaults).
- After each change: commit and push; CI tests, builds the APK + Windows installer/zip and publishes one release.
  Build logs land on the `ci-log` branch. Docs-only commits use [skip ci].
- Ask before adding a new library.
