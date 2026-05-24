# Lawn Mapper

Native Android camera overlay app for mapping lawn sections with persistent GPS and phone-angle anchored labels.

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
