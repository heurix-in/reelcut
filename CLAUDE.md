# Reelcut – Android video editor

Spec doc (phases, design, acceptance checklist): https://claude.ai/artifact/M2mWUvGDY9BkSDaFDuJx4c
Progress and known issues: PROGRESS.md.

## Rules
- Kotlin, Jetpack Compose, Material 3, MVVM, coroutines/StateFlow. Manual DI in `AppContainer`.
- Media3: ExoPlayer with clipped MediaItems for preview, Transformer for export. No FFmpeg.
- `Project` (JSON) is the single source of truth; preview and export both go through `CompositionBuilder`.
- All times in microseconds (Long). Timeline maths lives in `data/Timeline.kt` with unit tests; keep it pure.
- Never block the main thread with decoding or file IO.
- Colours, type and shapes live in `ui/theme/Theme.kt`; don't hard-code them in screens.
  Look: dark #252525, accent #EEEA7A (always a fill with dark content), stone #5C574D, round buttons, pills.
- Do not change the Project schema without keeping old JSON readable (`ignoreUnknownKeys`, defaults).
- After each change: `./gradlew testDebugUnitTest assembleRelease`, then commit. CI publishes the APK as a GitHub release.
- Ask before adding a new library.
