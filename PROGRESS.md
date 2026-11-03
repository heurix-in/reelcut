# Progress

## Done – Phase 1 (first build)
- Home: "Create and make" header, project cards (cover, duration, date, edit), rename/duplicate/delete, Exports tab (Android 10+)
- Import via the system photo picker (no storage permission), persistent read access, missing-media check
- Editor: full-bleed preview, tool rail (Trim, Split, Duplicate, Reorder, Delete), floating timeline strip with centre playhead, pinch-zoom, "+" between clips to insert, yellow add menu, time chips per clip
- Trim panel on the whole source with yellow handles; Reorder panel with long-press drag
- Undo/redo (50 steps), autosave 500 ms after each edit
- Export sheet: 720p/1080p, 30/60 fps, quality; progress, cancel, saved to Movies/Reelcut, Play/Share

## Done – Phase 2 (canvas & speed)
- Canvas tool: Original, 9:16, 4:5, 1:1, 16:9; black or blurred background (preview matches export framing)
- Photos as clips (picked with videos), with a length panel (0.1–30 s)
- Speed 0.25×–4× per clip with presets, slider and "apply to all"; timeline badges
- Volume 0–200% and mute per clip ("apply to all"); above 100% is heard on export only
- Frame tool: rotate 90°, mirror, fit/fill
- Panel edits preview live and land as one undo step on Done; Cancel restores

## Done – Stage A + Windows (1 Nov 2026, release v0.2.5)
- Restructured into one shared Compose Multiplatform app; Windows .exe installer + portable zip built by CI
- InShot-style editor in Gixlo colours: video on top, playback row (undo/redo, play, time), labelled toolbar
  (main: Canvas, Text*, Audio*, Logo*, Filter*, Reorder, Add; clip: Split, Trim/Length, Speed, Volume, Rotate,
  Duplicate, Reorder, Delete), ruler timeline with "+" and "Mute clip", duration badge on the selected clip
  (* = marked "Soon")
- Windows: FFmpeg preview with sound, mouse-wheel zoom on the timeline, export to Videos\Reelcut

## Done – Text, Logo, background colour (1 Nov 2026, release v0.2.7)
- Text: type, font (Clean/Bold/Serif/Mono), size, opacity, colour, outline, label box, soft shadow,
  alignment, timing (start/end at playhead, whole video); drag to move, pinch or yellow corner dot
  to resize/rotate; tap text on the video or its bar under the timeline to edit
- Logo: any picture (PNG keeps transparency), size, opacity, quick positions, timing
- Canvas background: any colour (swatches + hue/shade sliders) or blur
- One drawing routine for preview and export; Android burns layers in with Media3 overlays,
  Windows with ffmpeg overlay

## Done – Audio (1 Nov 2026, release v0.2.8)
- Audio tool: add music from the phone/PC (several files at once), record a voice-over, switch the video's own sound on/off
- Voice-over: the video plays silently from the playhead while the microphone records; stops at the end of the video
  or with Stop; Android asks for microphone permission on first use
- Per sound: volume 0–200%, fade in / fade out, cut from start / end, start position (playhead); delete
- Bars under the timeline (cream = music, coral = voice), tap one to edit; sounds past the end of the video are cut
- Preview mixes the sounds with the video (Android: one player per sound; Windows: one sound line per sound);
  export mixes them in (Android: Media3 audio sequences with a silent WAV for delays and a fade/volume processor;
  Windows: ffmpeg afade/volume/adelay/amix with a limiter)
- Project schema 4 (`audio` list; older projects open unchanged)

## Done – Blank frame, Windows polish (2 Nov 2026, release v0.2.9)
- "Blank" tool: adds a 3 s black frame at the playhead (a tiny PNG handled like a photo, so both engines export it);
  change its length with Length
- Windows: the Explorer-style Open window (via PowerShell, falls back to Java's old dialog) for media and music
- Windows: panels capped at 640 dp and centred instead of stretching across the window

## Done – Hardening, tests, audio gaps (3 Nov 2026)
- Projects no longer vanish silently: a project file that cannot be read is listed (Home shows a note you can hide)
  and left untouched on disk; values this build does not know fall back to defaults so the project still opens
- Saves are atomic on Windows too (the old delete-then-rename fallback could lose a project if the app died between
  the two steps); the file is flushed to disk before the swap; interrupted saves are recovered or cleaned up on start
- Saving is not cancelled half-way by a newer edit; a failed autosave (storage full) shows a message instead of crashing
- Recorded voice-overs are deleted with their project, only when no other project (such as a duplicate) uses them,
  every project is readable, and the file sits directly in the app's voice folder
- Clip tool "Extract": a clip's sound becomes its own sound under the timeline and the clip goes silent (one undo step;
  not for photos, silent, muted or sped-up clips)
- Sticker tool: pick an emoji; it becomes big text you can move, resize and time like any text
- Tests: 123 (was 22): repository and file store, voice cleanup, and `EditorViewModel` (70) against fake engine,
  preview and recorder. `kotlinx-coroutines-test` added as a test-only dependency. JDK-only tests live in
  `src/jvmSharedTest` (attached to the Android unit tests and the desktop tests)
- Checked by temporarily breaking behaviour on purpose (undo cap, cancel, autosave delay, export guard, deletion
  safety checks, ...): each one was caught by a test

## Verified vs not (3 Nov 2026 work)
- Compiled and tested on the JVM (desktop target) with a throwaway harness: all shared code, the desktop engine and
  all 123 tests pass. The harness stubs the AndroidX lifecycle classes, so the `EditorViewModel` tests have run
  against a stand-in `ViewModel`, not the real library
- NOT built or run: anything in `androidMain` (Media3), the Android unit-test run, the Windows app itself, and no
  screen was looked at. CI must confirm: that `FileProjectStoreTest` and `VoiceFolderTest` appear in the Android
  test report (the source-set hook is matched by name), and that the extracted sound plays in preview and export
  on both platforms (the code path reads a video file as a sound source)

## Known gaps / next
- Import uses the system picker instead of a custom in-app grid (keeps the app permission-free)
- Light theme not done yet (dark only)
- Preview may show a brief hitch at clip joins; export is seamless
- Windows preview: sped-up clips play silent (sound follows speed on export); photos ignore EXIF rotation
- Windows editor still uses the phone layout (small video, full-width timeline); a proper desktop layout is planned
- Blank frame colour is black only for now
- Next: text animations (fade, slide, pop), then filters and transitions. These need Android export code that can only
  be built by CI, so they go in a separate step with a CI round trip
- Cloud sync would need the INTERNET permission, which the spec rules out ("fully offline, no network permission");
  decide that before starting it
- Audio: boosts above 100% are heard on export only; no waveform on the bars yet; an extracted sound stays where it
  is if its clip is later moved or cut; extracting from a clip is not offered at a speed other than 1x
- A project's final save when you leave the editor (`onCleared`) is not covered by tests

## Decisions (31 Oct 2026)
1. One shared app for Android and Windows (Compose Multiplatform): shared UI, timeline and project code;
   Media3 engine on Android, bundled FFmpeg engine on Windows; CI builds the APK and a Windows .exe per release.
2. Stage A layout follows InShot (video on top, playback row, labelled bottom toolbar that changes with
   selection, ruler timeline, Mute Clip) but keeps the dark + yellow Gixlo colours.
3. Order after Stage A: text & logo, then audio, then filters & animations.
