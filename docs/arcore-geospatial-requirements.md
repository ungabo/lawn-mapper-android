# ARCore Geospatial Version Requirements

## Status

The installed app is the non-ARCore version. It uses Camera2, GPS, and rotation sensors.

An ARCore version is feasible for this phone, but the persistent version that lets lawn labels relocalize on a later visit needs ARCore Geospatial/VPS authorization through Google Cloud. I did not build that version yet because the app needs a real Google Cloud ARCore API credential that should be owned by the repo/account owner.

## Why Plain ARCore Is Not Enough

Plain ARCore local anchors are good for keeping content stable while one AR session is running. They are not enough for the main goal of returning later and seeing the same lawn labels after the app restarts.

For that, the app should use ARCore Geospatial anchors:

- Store latitude, longitude, altitude, heading, and shape metadata.
- Let ARCore/VPS estimate the camera's real-world pose.
- Reproject the saved lawn annotations from geospatial anchors when localization accuracy is good enough.

## Current Phone Fit

The wireless ADB phone detected earlier was:

```text
Model: SM_A546U
Product/device family: a54x
```

Google lists Samsung Galaxy A54 5G as ARCore-supported with Depth API support:

```text
https://developers.google.com/ar/devices
```

## Required External Setup

1. Create or choose a Google Cloud project.
2. Enable the ARCore API for that project.
3. Create Android API credentials for ARCore/Geospatial.
4. Restrict the API key to this package and certificate:

```text
Package name: com.gabecodex.lawnmapper
Debug SHA-1: BE:15:D0:23:3B:35:18:01:EA:D2:8F:46:22:A6:CD:C0:70:2A:40:E0
```

5. Decide whether the production app will use:

- API key authorization, easiest for a prototype.
- Keyless authorization, better for a published app but requires release signing and Play/App Integrity setup.

6. Make sure Google Play Services for AR is installed and up to date on the phone.
7. Test outdoors in daylight with good GPS and internet. VPS coverage depends on Google's mapped imagery for the location.

Official setup references:

- ARCore Geospatial quickstart: https://developers.google.com/ar/develop/java/geospatial/quickstart
- Enable ARCore in Android apps: https://developers.google.com/ar/develop/java/enable-arcore
- ARCore SDK repository/releases: https://github.com/google-ar/arcore-android-sdk
- ARCore Maven metadata: https://dl.google.com/dl/android/maven2/com/google/ar/core/maven-metadata.xml

## App Changes Needed

The current app uses Camera2 directly. ARCore owns the camera during an AR session, so the ARCore version should replace the preview stack with:

- `GLSurfaceView` or another OpenGL renderer.
- ARCore `Session`.
- ARCore camera background renderer.
- Geospatial mode enabled in `Config`.
- Per-frame `Frame` and `Camera` pose updates.
- A renderer for annotation geometry and label billboards.

The annotation model can mostly stay, but it should gain:

- Geospatial anchor fields.
- Localization accuracy at creation.
- Anchor resolution state.
- Fallback display using the current GPS/heading approach when ARCore localization is not ready.

## Suggested Implementation Plan

1. Add a Gradle Android build alongside the current manual SDK build.
2. Add dependency on the current ARCore Android SDK, currently `com.google.ar:core:1.54.0` as of the April 2026 ARCore SDK release.
3. Mark ARCore as optional in the manifest so the existing non-AR mode still works.
4. Add `INTERNET` permission for Geospatial/VPS.
5. Add an AR mode switch:

```text
Standard mode: current Camera2 GPS/heading overlay
AR mode: ARCore Geospatial overlay when authorized and localized
```

6. Store geospatial anchors for points/shapes and rehydrate them on launch.
7. Render saved shapes only after localization reaches acceptable horizontal and heading accuracy.
8. Keep the current snapshot pipeline or implement an AR framebuffer capture path.

## Practical Accuracy Expectation

ARCore Geospatial should be much better than raw GPS/compass when VPS localization is available, but it still depends on:

- Outdoor visibility.
- GPS quality.
- Magnetometer quality.
- Internet connection.
- Google VPS coverage at the lawn location.
- Similar visual conditions when returning later.

If VPS is unavailable for the lawn, the app should fall back to the current GPS/heading projection.
