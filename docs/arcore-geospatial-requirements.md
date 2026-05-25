# ARCore Geospatial Version Requirements

## Status

The app includes a local ARCore ground-lock mode. It uses ARCore anchors on detected horizontal planes, so shapes stay locked to the lawn while the AR session is running.

The app can save/load named projects now. Projects store a GPS origin plus compass-aligned ground-plane offsets, and loading recreates the saved anchors automatically once GPS, compass, AR tracking, and a horizontal ground plane are ready.

This is a practical prototype, not true visual/geospatial relocalization. The fully persistent version that relocalizes lawn labels more tightly on a later visit still needs ARCore Geospatial/VPS authorization through Google Cloud. I did not build that part yet because the app needs a real Google Cloud ARCore API credential that should be owned by the repo/account owner.

## Why Plain ARCore Is Not Enough

Plain ARCore local anchors are good for keeping content stable while one AR session is running. They are not enough for the main goal of returning later and seeing the same lawn labels after the app restarts.

The current app bridges that gap with phone GPS and compass heading. That can be useful in a yard, but it inherits raw GPS and magnetometer error, so shapes can still be offset after walking away or reopening later.

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

## App Changes Needed For True Geospatial

The current launcher already uses ARCore for the camera/session. The true Geospatial version should extend that ARCore stack with:

- ARCore `Session`.
- Geospatial mode enabled in `Config`.
- Per-frame `Frame` and `Camera` pose updates.
- Earth/geospatial pose and accuracy checks before showing restored project anchors.

The annotation model can mostly stay, but it should gain:

- Geospatial anchor fields.
- Localization accuracy at creation.
- Anchor resolution state.
- Fallback display using the current GPS/heading approach when ARCore localization is not ready.

## Suggested Implementation Plan

1. Add the Google Cloud ARCore API credential to the app using the repo owner's project.
2. Enable geospatial mode in the existing ARCore `Config`.
3. Wait for `Earth` tracking and acceptable horizontal/heading accuracy before saving or restoring geospatial anchors.
4. Store geospatial anchor fields alongside the current GPS-origin project data.
5. Resolve saved geospatial anchors on load, then render shapes only after anchor tracking is stable.
6. Keep the current GPS/compass project loader as a fallback when Geospatial/VPS is unavailable.

## Practical Accuracy Expectation

ARCore Geospatial should be much better than raw GPS/compass when VPS localization is available, but it still depends on:

- Outdoor visibility.
- GPS quality.
- Magnetometer quality.
- Internet connection.
- Google VPS coverage at the lawn location.
- Similar visual conditions when returning later.

If VPS is unavailable for the lawn, the app should fall back to the current GPS/heading projection.
