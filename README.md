# Cam – a bare-bones, full-screen camera viewfinder for Android

A tiny Android app that does exactly one thing: it opens the camera and shows the live image,
edge to edge, with **no UI at all** – no buttons, no settings, no status bar, no navigation bar,
and no notch / punch-hole letterboxing. It can't record video or take photos; it is only a viewfinder.

Built and tested around the **POCO X6** (Camera2 API), but should work on any recent Android phone.

## Features

- Opens straight into the camera preview – nothing else on screen
- Truly full screen: the display cutout area is filled too
- Prefers the **widest back camera** (ultra-wide, ~0.6x on the POCO X6) at **1920x1080**
- Rotates freely (portrait, landscape, reverse) with a correct center-crop, no stretching
- Screen stays on while the app is open
- No dependencies, no network access, only the `CAMERA` permission

## Hidden controls

The screen stays completely clean. Two invisible gestures exist:

| Gesture | Action |
|---|---|
| **Triple-tap the top-left corner** (within a 150 dp square) | Toggle between **0.6x ultra-wide** (default) and **1.0x main camera with a 60 fps attempt** |
| **Long-press anywhere** | Show / hide a diagnostics panel (cameras found, selected camera, errors, live FPS) |

The app always starts in the 0.6x ultra-wide mode.

## Install (build the APK with GitHub Actions – no PC needed)

1. Create a new GitHub repository and upload the contents of this project (keep the `.github` folder).
2. Open the **Actions** tab and wait for the **Build APK** workflow to finish
   (or run it manually with *Run workflow*).
3. Open the finished run, download the **Cam-APK** artifact, unzip it and install `app-debug.apk`
   on your phone (you may need to allow installs from unknown sources).

You can also open the project in Android Studio and run *Build > Build APK(s)*.

Requirements: Android 11+ (`minSdk 30`).

## Known limitations

- **Frame rate depends on the phone.** On the POCO X6 the camera driver advertises only 30 fps
  for third-party apps on every camera, so the ultra-wide runs at 30 fps. The 1.0x mode *requests*
  60 fps anyway; whether the phone actually delivers it can be checked in the diagnostics panel
  (long-press, look at `CANLI FPS`). If it stays at ~30, the phone does not expose 60 fps to
  third-party apps.
- **Image quality is the raw 1080p sensor stream.** The stock camera app uses private vendor
  processing (HDR, sharpening, multi-frame noise reduction) that third-party apps cannot access,
  so the picture looks a bit softer than in the built-in camera.
- 16:9 video on a taller (e.g. 20:9) screen is center-cropped to fill the display, so a part of the
  edges is cut off.
- Some manufacturers (e.g. Xiaomi) restrict ultra-wide access for third-party apps. The app probes
  hidden camera IDs automatically and falls back to the next best camera if one fails.

## Project structure

```
app/src/main/java/az/cam/MainActivity.kt   # all of the logic (Camera2 + TextureView)
app/src/main/AndroidManifest.xml
app/src/main/res/values/styles.xml         # full-screen, cutout "always" theme
.github/workflows/build.yml                # builds a debug APK on every push
```

## Tweaking

- Resolution: change `S1080` in `MainActivity.kt`.
- Switch the startup mode: set `mainMode` to `true` in `MainActivity.kt`.
- Target fps: edit the `Range(60, 60)` used in `openBest()`.

## License

MIT – do whatever you like.
