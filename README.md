# android-client

Kotlin Android app that turns your phone into a VR display + head tracker.

## Features
- **Madgwick IMU fusion** — gyro + accelerometer → stable quaternion at ~250Hz
- **UDP head tracking** → sends to PC driver on port 6000
- **H.264 hardware decode** via MediaCodec → renders on port 6001
- **Barrel distortion GLSL shader** — simulates VR lens correction
- **Chromatic aberration** per eye channel
- **Vignette** effect at lens edges
- **Glassmorphism UI** — connection screen with glass card aesthetic

## Key Files

| File | Purpose |
|---|---|
| `tracking/MadgwickFilter.kt` | IMU sensor fusion |
| `tracking/HeadTrackingSender.kt` | UDP quaternion sender |
| `streaming/VideoStreamReceiver.kt` | H.264 decode + fragment reassembly |
| `renderer/VRRenderer.kt` | OpenGL ES barrel distortion renderer |
| `ui/MainActivity.kt` | Main activity + connection UI |

## Build via GitHub Actions
Push to `main` → APK is built automatically → download from Actions artifacts.

## Manual build (Termux)
```bash
cd android-client
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Tuning lens distortion
Edit `VRRenderer.kt`:
```kotlin
private var k1 = 0.22f   // increase for more barrel distortion
private var k2 = 0.24f   // fine-tune for your specific cardboard lens
```
