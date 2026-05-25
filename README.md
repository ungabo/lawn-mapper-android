# Lawn Mapper

Native Android lawn annotation app.

The app uses ARCore to detect horizontal ground/lawn planes and anchors points, boxes, circles, and freehand shapes in 3D space so they stay on the lawn as you move the phone.

## Build

```powershell
.\scripts\build.ps1
```

## Install on a connected phone

Enable USB debugging, connect the phone, accept the trust prompt, then run:

```powershell
.\scripts\build.ps1 -Install
```

The generated APK is written to:

```text
build\outputs\lawnmapper-debug.apk
```

The repo also keeps the latest checked-in APK at:

```text
apk\lawnmapper-debug.apk
```

Snapshots are taken directly from AR mode and saved into the phone gallery under `Pictures/LawnMapper`. Use `Menu > View snapshots` to view and share them.

## AR Ground Lock

In AR mode:

1. Move the phone slowly over the lawn until the status says a ground plane is found.
2. Draw a point, box, circle, or freehand shape on the lawn area.
3. The app raycasts the drawn points onto the detected horizontal plane.
4. The shape is stored as local 3D coordinates attached to an ARCore anchor.

This locks shapes while the AR session is running. Reopening the app later and restoring exact lawn positions still requires ARCore Geospatial/VPS setup; see `docs/arcore-geospatial-requirements.md`.

## Controls

- `Snap`: save the camera image with AR shapes and labels.
- `Point`: tap the lawn for a labeled dot.
- `Box`: drag across a lawn area.
- `Circle`: drag across a circular or oval area.
- `Free`: draw an irregular outline.
- `Erase`: tap or drag through a shape to remove it.
- `Edit`: tap a shape or label to rename or delete it.
- `Menu`: view/share snapshots, open Help, or clear anchors.
- `Menu > Save project`: save current AR shapes/labels. New projects default to a human-readable date/time name.
- `Menu > Load project`: choose a saved project, then tap the lawn to place it on the current AR ground plane.
- `Menu > Rename project`: rename a saved project.
- `Menu > Delete project`: delete a saved project file.

## Project Save/Load

Projects are saved privately inside the app. They store the current AR shapes, labels, colors, and ground-plane-relative geometry.

Because local ARCore coordinates reset when the app restarts, loading a project requires a placement step: select the project, then tap the lawn where that saved project should be placed. Automatic return-to-the-exact-same-yard-position still requires ARCore Geospatial/VPS setup.
