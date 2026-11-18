# Lumen for Android (Galaxy S22 Ultra)

Manual camera built with Kotlin, Jetpack Compose and CameraX.

## Features
- Photo and video (UHD where available), saved to Pictures/Lumen and Movies/Lumen
- Lens buttons 0.6× / 1× / 3× / 10× (the phone switches the real lens)
- Manual ISO, shutter, focus and exposure compensation (Camera2 interop)
- 70 colour looks ported from the iPhone app, live on the preview and applied to saved photos
  (vignette, grain, glow, light leaks, dust, pixel / CRT / glass effects are drawn onto saved photos)
- 88 presets in 9 categories (Food, People, Indoor, Landscape, Night, Street, Weather, Creative, Pro) with the same
  tips. Presets set look, strength, grid, crop ratio, burst, steady wait, manual ISO/shutter and exposure bias.
  Presets that need STARS / NIGHT / PANO / TRAIL modes apply their look and say the mode isn't available yet.
- `tools/gen_from_ios.py` regenerates `LookData.kt` and `PresetData.kt` from the iPhone sources
- **Live look preview**: colour, vignette, grain, light leaks, pixel, CRT scanlines, frosted glass and neon edges are drawn live (Android 13+)
- Volume-button shutter, 3 / 10 s timer, live histogram, 4K / 1080p, 30 / 60 fps and HDR video options, photos → PDF (1, 2 or 4 per page), long exposure and light-trail stacking
- Interval mode (a photo every 2 / 5 / 10 / 30 s), burst (5 shots), long-press to lock focus & exposure, focus peaking, zebra, level line, white balance presets, gallery thumbnail
- **HDR** (3 shots fused), **Portrait** blur (on-device ML Kit), **My Look** sliders (saved), flare strength slider, home-screen long-press shortcuts, haptics, saved settings, first-run tour
- **Night mode**: shoots a burst for 3 / 6 / 10 s, aligns the frames and averages them (cleaner, brighter low-light photos)
- **RAW**: saves a DNG plus a JPEG (RAW turns video and the flare watcher off while it is on)
- **Flare Fix**: removes the milky veil caused by lens flare, plus a live "flare detected" warning

## About lens flare
Flare is partly physical (light bouncing inside the lens stack). The app removes the haze it causes
(lifted blacks, low contrast) but cannot erase ghost shapes. Wipe the lens first, and shade it from the light.

## Build
Open this folder in Android Studio and press Run, or:

    gradle wrapper && ./gradlew installDebug      # with the phone connected, USB debugging on

## Not yet included
50 MP, star map, panorama, portrait blur, PDF export.
