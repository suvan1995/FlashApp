# Flashlight Buddy

When your phone's flashlight turns on, your wallpaper turns into a dark forest where a little explorer walks with a flashlight. Point your phone left, right, up or down and the explorer points the beam the same way. Turn the flashlight off and your normal background comes back.

## Install on your phone

1. Open the **Releases** page of this repo on your Android phone and download `FlashlightBuddy.apk` from the "latest" release.
2. Open the file and allow installing from your browser if Android asks.
3. Open **Flashlight Buddy** and tap **Set as wallpaper**.
4. Optional: tap **Choose main background photo** to pick the picture shown while the flashlight is off. Otherwise a cartoon daytime scene is used.
5. Turn on the flashlight from quick settings and go to your home screen.

## How it works

- `android/` is a small Kotlin live wallpaper. It listens for the system flashlight with `CameraManager.TorchCallback` (no permissions needed), so it reacts no matter how the torch is turned on. The rotation-vector sensor tells it where the back of the phone points; the sensor only runs while the flashlight is on.
- `web/` is a browser version of the same scene (a PWA). A browser can turn the flashlight on itself, but it cannot see the system flashlight, so the web version only reacts to its own button.
- Every push to `main` builds the APK with GitHub Actions (`.github/workflows/build-apk.yml`) and attaches it to the "latest" release.

The scene is drawn in code in `android/app/src/main/java/com/flashbuddy/app/Scene.kt`, mirrored in `web/scene.js`.
