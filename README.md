# Lawn Mapper

Native Android lawn annotation app.

The launcher now opens AR ground-lock mode. It uses ARCore to detect horizontal ground/lawn planes and anchors points, boxes, circles, and freehand shapes in 3D space so they stay on the lawn as you move the phone.

The older Camera2 GPS/heading overlay is still available from the `2D` toolbar button as a fallback.

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

Snapshots are saved into the phone gallery under `Pictures/LawnMapper`.

## AR Ground Lock

In AR mode:

1. Move the phone slowly over the lawn until the status says a ground plane is found.
2. Draw a point, box, circle, or freehand shape on the lawn area.
3. The app raycasts the drawn points onto the detected horizontal plane.
4. The shape is stored as local 3D coordinates attached to an ARCore anchor.

This locks shapes while the AR session is running. Reopening the app later and restoring exact lawn positions still requires ARCore Geospatial/VPS setup; see `docs/arcore-geospatial-requirements.md`.
