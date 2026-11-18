# Lumen for Android (Galaxy S22 Ultra)

Manual camera built with Kotlin, Jetpack Compose and CameraX.

## Features
- Photo and video (UHD where available), saved to Pictures/Lumen and Movies/Lumen
- Lens buttons 0.6× / 1× / 3× / 10× (the phone switches the real lens)
- Manual ISO, shutter, focus and exposure compensation (Camera2 interop)
- 14 colour looks, live on the preview and applied to saved photos (incl. INDOOR TRUE / INDOOR COZY)
- **Flare Fix**: removes the milky veil caused by lens flare, plus a live "flare detected" warning

## About lens flare
Flare is partly physical (light bouncing inside the lens stack). The app removes the haze it causes
(lifted blacks, low contrast) but cannot erase ghost shapes. Wipe the lens first, and shade it from the light.

## Build
Open this folder in Android Studio and press Run, or:

    gradle wrapper && ./gradlew installDebug      # with the phone connected, USB debugging on

## Not yet included
RAW, 50 MP, Night mode, star map, panorama, portrait blur, PDF export.
