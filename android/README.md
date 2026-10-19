# Quake II RTX for Android

An Android port of [NVIDIA's Quake II RTX](https://github.com/NVIDIA/Q2RTX): the full
Vulkan path tracer, running on phones and tablets with hardware ray tracing.

## Requirements

**GPU.** Q2RTX has no rasterized fallback; it needs Vulkan 1.2 with `VK_KHR_ray_query` and
`VK_KHR_acceleration_structure`. On Android that currently means:

| SoC | GPU |
|---|---|
| Snapdragon 8 Gen 2 / 8 Gen 3 / 8 Elite and newer | Adreno 740 / 750 / 830 |
| Exynos 2200 / 2400 / 2500 | Xclipse 920 / 940 / 950 |
| Dimensity 9200 / 9300 / 9400 and newer | Immortalis-G715 / G720 / G925 |

Other devices get a clear "No ray tracing capable GPU found" error at startup.
The renderer uses the ray-query backend (`ray_tracing_api query`), which is what mobile drivers expose.

**Game data** (not included, same files as the desktop game). From the `baseq2` folder of a
Quake II RTX install (Steam, or the Windows/Linux release on GitHub):

- `q2rtx_media.pkz` and `blue_noise.pkz` — NVIDIA's RTX materials and textures
- `pak0.pak` (plus `pak1.pak`, `pak2.pak` and `players/` for the full game) — the shareware
  demo pak that ships with Q2RTX works for the first levels

Compiled shaders (`shaders.pkz`) are built into the APK.

## Installing game data

1. Copy the `baseq2` folder to the phone (USB, cloud drive, SD card…).
2. Open the app, tap **Import game data…** and pick that folder (or the folder that contains it).
   Files are copied into the app's own storage; nothing else on the device is touched and no storage
   permission is needed. Import resumes if interrupted.
3. Tap **Play**.

Alternatively, copy the files over USB straight to
`Android/data/io.github.q2rtx/files/baseq2/`.

## Controls

**Touch** (shown while playing)

- Left side: floating move stick — touch anywhere and drag.
- Right side: drag to look.
- **FIRE** (dragging it also aims), **JUMP**, **DUCK**, **WPN>** / **<WPN** (cycle weapons),
  **USE** (use selected item), **INV** (inventory), **MENU** (top left).
- Menus work by tapping; the Back button acts as Escape.

**Game controller** (any controller Android/SDL recognizes)

| Input | In game | In menus |
|---|---|---|
| Left stick / right stick | Move / look | |
| Right trigger / left trigger | Fire / jump | Select (RT) |
| A / B | Jump / crouch | Select / back |
| X / Y | Use item / next weapon | |
| LB / RB, D-pad up/down | Previous / next weapon | Navigate |
| D-pad left/right | Previous / next inventory item | Navigate |
| Start / Back (View) | Menu / inventory | Back |
| Left stick click / right stick click | Scoreboard / center view | |

Keyboard and mouse (USB, Bluetooth, Samsung DeX, Chromebooks) work like on the desktop.

Console variables: `touch_enable`, `touch_sens`, `touch_alpha`, `touch_stick_size`,
`joy_sens`, `joy_invert`, `joy_deadzone`.

## Performance

Path tracing is heavy for a phone. Mobile defaults (applied before your saved config, so menu
changes stick):

```
drs_enable 1; drs_target 30; drs_minscale 25; drs_maxscale 75   // dynamic resolution
flt_fsr_enable 1                                               // FSR 1.0 upscaling
pt_num_bounce_rays 0.5; pt_reflect_refract 1; pt_caustics 0
gr_enable 0; physical_sky_draw_clouds 0; cl_maxfps 60
```

If it is too slow, lower `drs_minscale`/`drs_maxscale`, set `pt_num_bounce_rays 0`, or
`drs_target 25`. Expect sustained clocks to drop as the phone heats up.

## Building

### GitHub Actions (no local setup)

Push this tree to a GitHub repository. `.github/workflows/android.yml` builds the APK on every
push; download it from the run's **Artifacts** (`q2rtx-android`).

### Locally

Needs JDK 17, the Android SDK with **NDK 27.2.12479018** and **CMake 3.22.1**
(SDK Manager → SDK Tools), plus a host C/C++ compiler, CMake and Ninja for the shader compiler.

```sh
git submodule update --init --recursive
android/build_shaders.sh                  # builds glslang for the host, compiles SPIR-V, writes assets/shaders.pkz
cd android
./gradlew assembleRelease                 # or open android/ in Android Studio
adb install app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the debug key so it installs directly; add your own
`signingConfig` before publishing anywhere.

### Debugging

```sh
adb logcat -s Q2RTX SDL SDL/APP          # engine console output goes to logcat
```

Settings, saves and screenshots live next to the data in `Android/data/io.github.q2rtx/files/baseq2/`.

## What the port changes

- **Build** (`CMakeLists.txt`, `src/CMakeLists.txt`, `extern/CMakeLists.txt`): Android branch that
  builds the client as `libmain.so` (entry point `SDL_main`), the game module as `libgame.so`, and
  SDL2 as a shared library; OpenAL uses OpenSL ES; GL renderer, curl, multi-GPU and the dedicated
  server are off; shaders compile on the host (`CONFIG_COMPILE_SHADERS`).
- **Platform** (`src/unix/system.c`, `tty.c`, `server/game.c`): data/home directory from
  `SDL_AndroidGetExternalStoragePath()`, console output to logcat, game module loaded from the APK's
  library directory, mobile default cvars.
- **Vulkan** (`src/refresh/vkpt/main.c`, `textures.c`, `god_rays.c`): debug-utils/debug-report instance
  extensions made optional; optional device features (robust buffer access, pipeline statistics,
  extended storage formats, min/max sampler reduction…) are masked by what the GPU supports instead
  of failing device creation; swapchain picks a supported composite-alpha mode, falls back to FIFO,
  renders unrotated with an identity pre-transform and ignores the resulting `VK_SUBOPTIMAL_KHR`;
  fixed a build break when device-group support is disabled. If the driver can't compile a ray
  query shader, the engine falls back to `spirv-opt`-processed builds of it (`.opt`, `.size`,
  `.legal`, made by `build_shaders.sh`) and, if all fail, compiles small probe shaders and reports
  which ray query feature the driver rejects.
- **Input** (`src/android/touch.c`, `unix/video/sdl.c`, `client/input.c`, `client/screen.c`): touch
  overlay, analog move/look and SDL GameController support; touches act as a mouse only in menus.
- **App** (`android/`): launcher with folder import (Storage Access Framework), SDL activity,
  bundled shaders extracted on install/update.

## Status

The native code is cross-compiled and link-checked for arm64 against bionic/API 29 and
Vulkan 1.1 exports, and the shaders compile, but this port has **not yet been run on a
ray-tracing phone**. Mobile Vulkan drivers are the main unknown: if a device fails, the
`adb logcat -s Q2RTX` output around `init_vulkan` shows which extension or feature is missing.
