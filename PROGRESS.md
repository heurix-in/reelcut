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

## Known gaps / next
- Import uses the system picker instead of a custom in-app grid (keeps the app permission-free)
- Light theme not done yet (dark only)
- Preview may show a brief hitch at clip joins; export is seamless
- Windows preview: sped-up clips play silent (sound follows speed on export); photos ignore EXIF rotation
- Next: Audio (music from device, voice-over, volume, fade), then filters and animations

## Decisions (31 Oct 2026)
1. One shared app for Android and Windows (Compose Multiplatform): shared UI, timeline and project code;
   Media3 engine on Android, bundled FFmpeg engine on Windows; CI builds the APK and a Windows .exe per release.
2. Stage A layout follows InShot (video on top, playback row, labelled bottom toolbar that changes with
   selection, ruler timeline, Mute Clip) but keeps the dark + yellow Gixlo colours.
3. Order after Stage A: text & logo, then audio, then filters & animations.
