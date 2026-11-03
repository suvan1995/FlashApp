# Flashlight Buddy

Keep your own wallpaper. When your phone's flashlight turns on, a little explorer appears walking through a dark forest with a flashlight, on your lock screen or home screen. Point your phone left, right, up or down and the explorer points the beam the same way. Turn the flashlight off and you're back to your own wallpaper.

## Install on your phone

1. Open the **Releases** page of this repo on your Android phone and download `FlashlightBuddy.apk` from the "latest" release.
2. Open the file and allow installing from your browser if Android asks (Play Protect may warn because it isn't from the Play Store; choose "Install anyway").
3. Open **Flashlight Buddy**, tap **Allow "Display over other apps"** and switch it on for Flashlight Buddy.
4. Back in the app, tap **Turn Flashlight Buddy on** and allow notifications. A quiet notification stays while it waits for the flashlight.
5. Turn on the flashlight from quick settings.

Options:
- **See-through** (off by default): on the home screen the scene floats over everything at about 80% opacity and you can keep tapping your apps through it. Off means it fills the screen; tap it to close.
- **Use as my wallpaper instead**: makes the animation your live wallpaper (this replaces your wallpaper).

## How it works

- `android/` is a small Kotlin app. A foreground service (`Watcher.kt`) listens for the system flashlight with `CameraManager.TorchCallback` and opens the scene over the lock or home screen; an optional live wallpaper uses the same scene. The rotation-vector sensor tells it where the back of the phone points; the sensor only runs while the flashlight is on.
- `web/` is a browser version of the same scene (a PWA). A browser can turn the flashlight on itself, but it cannot see the system flashlight, so the web version only reacts to its own button.
- Every push to `main` builds the APK with GitHub Actions (`.github/workflows/build-apk.yml`) and attaches it to the "latest" release.

The scene is drawn in code in `android/app/src/main/java/com/flashbuddy/app/Scene.kt`, mirrored in `web/scene.js`.
