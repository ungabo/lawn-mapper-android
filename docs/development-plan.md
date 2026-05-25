# Lawn Mapper Development Plan

## Goal

Build an installable Android app that lets you look at a lawn through the phone camera, draw labeled areas on top of the live view, and see those labeled areas again when you return to roughly the same GPS location and point the phone in the same direction.

## Core Behavior

- ARCore ground-lock camera mode for 3D lawn-anchored drawing.
- Shape tools:
  - Point labels for quick notes.
  - Rectangles for beds/plots.
  - Circles/ovals for patches.
  - Freehand irregular shapes for grass, beds, borders, or odd areas.
- Label entry with typed text after creating a shape.
- Edit mode to reopen/change labels or delete an annotation.
- Erase mode with a circular brush that removes whole annotations it touches.
- Pinch zoom that controls the camera crop and updates annotation projection.
- Snapshot button that saves the current AR camera view with labels/shapes into the phone gallery.
- Snapshot gallery for viewing and sharing saved images.
- Help menu explaining every tool.
- Project save/load/delete/rename.
- Local persistence in app-private JSON so annotations survive app restarts.

## Persistence Model

Each annotation stores:

- Shape type.
- Label.
- Creation time.
- Color.
- GPS origin latitude/longitude/altitude/accuracy when created.
- Device pose when created.
- One or more angular anchors:
  - Bearing from the device heading.
  - Elevation from the device pitch.

The launcher AR mode uses ARCore plane tracking for the active session. Each drawn screen point is raycast onto a detected horizontal ground plane and stored relative to an ARCore anchor, so the overlay follows real camera translation and parallax while the app is open.

This gives practical in-session ground locking now, while later-session precision still needs a cloud/geospatial relocalization service.

## Project Save/Load

Saved projects live in app-private JSON files under the app data directory. New projects default to a human-readable date/time name such as `May 25, 2026 3:42 PM`.

Each project stores:

- Project id, name, created time, updated time.
- Shape type, label, color.
- Shape points and label point relative to a saved project origin.

Loading is a deliberate placement flow: the user selects a project, then taps the currently detected lawn/ground plane. The app creates fresh ARCore anchors in the current session and restores the saved shapes relative to that tap.

## Accuracy Notes

Phone GPS is often accurate to 3-15 meters outdoors, sometimes worse near houses, trees, or fences. A typical yard is smaller than the error range, so this app uses GPS to decide which saved annotations belong nearby, then uses compass/rotation sensors and camera field of view to place them back on screen.

This means:

- It should work best when you stand near the same spot and face the same lawn area.
- It will not be centimeter-accurate.
- It may drift when the compass is disturbed by metal, vehicles, buildings, or magnetic cases.
- For survey-grade placement, a future version should use ARCore world tracking, a visual relocalization flow, or ARCore Geospatial if the target device and Google Cloud setup are available.

## Permissions

Required:

- `CAMERA` for live preview.
- `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` for GPS-scoped annotations.

Storage:

- Modern Android versions do not require storage permission to write a new image through `MediaStore`.
- The manifest includes legacy `WRITE_EXTERNAL_STORAGE` only up to Android 9/API 28 for older phones.

## Implementation

The app is intentionally dependency-light:

- Java Activity using platform Android APIs.
- Camera2 for preview and digital zoom.
- LocationManager for GPS/network location.
- Rotation-vector sensor for heading/pitch/roll.
- Custom View for shape rendering and touch handling.
- `org.json` for local annotation storage.
- MediaStore for PNG snapshots.

## Files

- `app/src/main/AndroidManifest.xml`: app metadata and permissions.
- `app/src/main/java/com/gabecodex/lawnmapper/MainActivity.java`: lifecycle, UI, permissions, location, sensors, snapshots.
- `CameraController.java`: Camera2 preview and zoom.
- `AnnotationOverlayView.java`: drawing tools, labels, projection, erase/edit hit testing.
- `LawnAnnotation.java`, `AnchorPoint.java`, `DevicePose.java`: annotation and pose model.
- `AnnotationStore.java`: JSON persistence.
- `scripts/build.ps1`: dependency-free Android SDK build, sign, and optional install.

## Test Plan

1. Build the APK with `.\scripts\build.ps1`.
2. Connect an Android phone with USB debugging enabled.
3. Install with `.\scripts\build.ps1 -Install`.
4. Launch Lawn Mapper.
5. Grant camera and location permissions.
6. Wait for GPS accuracy to appear in the status strip.
7. Draw a point, box, circle, and freehand shape.
8. Enter labels for each.
9. Pinch zoom and confirm the camera zoom changes.
10. Tap Snap and confirm an image appears under `Pictures/LawnMapper`.
11. Close and reopen the app; annotations should reload.
12. Return near the same position and point in the same direction; annotations should reappear.

## Future Enhancements

- ARCore visual tracking for more stable near-field placement.
- Calibration step where the user marks two known lawn corners to improve projection.
- Export/import annotation maps.
- Layer visibility toggles.
- Per-label colors and plant/category icons.
- Cloud backup.
