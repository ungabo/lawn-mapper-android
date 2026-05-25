# Lawn Mapper Development Plan

## Goal

Build an installable Android app that lets you look at a lawn through the phone camera, draw labeled areas on top of the live view, and see those labeled areas again when you return to roughly the same GPS location.

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
- Snapshot button that saves the current AR camera view with labels/shapes into the phone gallery.
- Snapshot gallery for viewing and sharing saved images.
- Help menu explaining every tool.
- Project save/load/delete/rename.
- New project action with a default human-readable date/time name.
- Autosave after drawing, editing, erasing, clearing, and project metadata actions.
- Local persistence in app-private JSON so annotations survive app restarts.

## Persistence Model

Each annotation stores:

- Shape type.
- Label.
- Creation time.
- Color.
- GPS origin latitude/longitude/altitude/accuracy for the project.
- Phone save pose and compass-aligned save direction.
- Per-shape observer pose.
- Per-point GPS coordinates for shape vertices and labels.
- Compass-aligned east/north/up meter offsets from that project origin.

The launcher AR mode uses ARCore plane tracking for the active session. Each drawn screen point is raycast onto a detected horizontal ground plane and stored relative to an ARCore anchor, so the overlay follows real camera translation and parallax while the app is open.

If ARCore cannot see a ground plane at the drawn screen point, the renderer intersects that screen ray with the last detected or estimated lawn height. This allows placing a lawn marker even when a bush or other object blocks the visible ground.

This gives practical in-session ground locking now. Later-session reloads use each saved point's GPS coordinate, compass heading, and the current AR ground estimate; tighter precision still needs a cloud/geospatial relocalization service.

## Project Save/Load

Saved projects live in app-private JSON files under the app data directory. New projects default to a human-readable date/time name such as `May 25, 2026 3:42 PM`.

Each project stores:

- Project id, name, created time, updated time.
- GPS origin latitude/longitude/altitude/accuracy.
- Phone save pose and compass-aligned save direction.
- Shape type, label, color.
- Per-shape observer pose.
- Per-point GPS coordinates for shape vertices and labels.
- Shape points and label point as east/north/up meter offsets from the saved GPS origin.

Loading is automatic: the user selects a project, then the app waits for GPS, compass, and AR tracking. It prefers the saved GPS coordinate for each shape vertex and label, aligns saved east/north offsets to the current compass heading, and creates fresh ARCore anchors in the current session on detected or estimated ground.

## Accuracy Notes

Phone GPS is often accurate to 3-15 meters outdoors, sometimes worse near houses, trees, or fences. A typical yard is smaller than the error range, so this app uses GPS plus compass heading to approximate the saved lawn location, then ARCore locks the restored shapes onto the currently detected ground plane.

This means:

- It should work best when you stand near the same spot with a good GPS fix.
- It will not be centimeter-accurate.
- It may drift when the compass is disturbed by metal, vehicles, buildings, or magnetic cases.
- For tighter return placement, a future version should use ARCore Geospatial/VPS if the target device and Google Cloud setup are available.

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
- ARCore `Session` with a `GLSurfaceView` background renderer.
- LocationManager for GPS/network location.
- Rotation-vector sensor for heading.
- Custom View for shape rendering and touch handling.
- `org.json` for local annotation storage.
- MediaStore for PNG snapshots.

## Files

- `app/src/main/AndroidManifest.xml`: app metadata and permissions.
- `app/src/main/java/com/gabecodex/lawnmapper/ArLawnActivity.java`: lifecycle, UI, permissions, location, sensors, snapshots, project actions.
- `ArLawnRenderer.java`: ARCore session rendering, plane raycasts, anchors, project save/load transforms.
- `ArOverlayView.java`: drawing tools, labels, projection, erase/edit hit testing.
- `SavedProject.java`, `SavedProjectStore.java`: app-private project JSON persistence.
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
9. Use erase/edit and confirm the active project autosaves after the change.
10. Tap Snap and confirm an image appears under `Pictures/LawnMapper`.
11. Use `Menu > Load project`; annotations should appear without requiring a placement tap once GPS/compass/AR ground tracking are ready.
12. Return near the same position; annotations should restore near the saved lawn location, subject to GPS/compass accuracy.

## Future Enhancements

- ARCore visual tracking for more stable near-field placement.
- Calibration step where the user marks two known lawn corners to improve projection.
- Export/import annotation maps.
- Layer visibility toggles.
- Per-label colors and plant/category icons.
- Cloud backup.
