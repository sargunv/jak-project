# Android roadmap

What works, and what's left before Jak 1 is playable on an arm64 Android
handheld such as the AYN Thor. Jak 2 and Jak 3 come after Jak 1.

## Status

- `libmain.so` and the extractor build for `arm64-v8a` and `x86_64` with NDK
  r30. The APK packages both. Pages are 16 KB aligned.
- The app installs a game from its ISO: the player picks the ISO in the
  launcher, and the extractor makes the game's data on the device. Jak 1 takes
  under a minute in Innkeeper and under the ARM translation emulator.
- On `x86_64` in Innkeeper, Jak 1 renders like the desktop build at every level
  the render comparison visits, with no OpenGL errors. A gamepad (a virtual one,
  through Innkeeper) and the keyboard work, sound plays through AAudio, and New
  Game starts the intro. Nobody has played it for real yet.
- On `arm64-v8a`, under the emulator's ARM translation and without a display,
  upstream's arm64 game code boots, reaches the title screen and loads into
  Sandover, both compiled on a desktop and by the app's own extractor. Rendering
  on arm64 needs hardware.
- The desktop build still builds, and `goalc-test` passes (1672 tests).

## Done

Build:

- [x] Android CMake presets. `OG_BUILD_TOOLS` skips the compiler, decompiler,
      LSP, tools and tests on Android.
- [x] `OG_WEB_REQUESTS` turns off libcurl, which needs OpenSSL. Web requests
      only fetch speedrun leaderboards, and fail quietly without it.
- [x] `-mavx` only on x86. On non-Apple arm64, `-march=armv8-a+crc` for
      `crc32.h`, and `-fsigned-char` because Linux and Android make `char`
      unsigned on arm64, unlike x86 and Apple.
- [x] Fixes for bionic: no `-lpthread` or `-lstdc++fs`, and fmt's missing
      `<cstdlib>`.
- [x] Draco staged `draco_features.h.new` in the source tree, so Gradle's
      parallel configures of the two ABIs deleted each other's file.

Linux arm64 runtime (overlaps upstream PR #4439):

- [x] `asm_funcs_arm64.s` used `;` comments, which separate statements in ELF
      assemblers. Now `//`.
- [x] `MAP_32BIT` only on x86. Linux arm64 CPU features from `getauxval`. NEON
      instead of AVX in the startup check.
- [x] The x86-64-only debugger register code reports itself unsupported on other
      CPUs.

App:

- [x] Gradle project (`android/`). `GameActivity` subclasses `SDLActivity`,
      compiles SDL's vendored Java in place, and has SDL call `main` in
      `libmain.so`. Intent extras pick the game and its arguments.
- [x] stdout and stderr go to logcat. Error dialogs use SDL message boxes
      instead of tinyfiledialogs, which forks a shell.
- [x] No Discord RPC on Android. Its registration ran `system()`, which forked
      the app and deadlocked the child.

Graphics:

- [x] OpenGL ES 3.2 context. OpenGL ES 3.2 loader in `third-party/glad-gles`.
- [x] `gles_compat.h`: 1D textures become 2D textures one texel high,
      `GL_UNSIGNED_INT_8_8_8_8_REV` becomes `GL_UNSIGNED_BYTE`, fixed-index
      primitive restart, `glClearDepthf`, multi-draw through
      `GL_EXT_multi_draw_arrays` or a loop, `glTexStorage2DMultisample`,
      wireframe through `GL_NV_polygon_mode` where it exists, and anisotropy
      enums from `GL_EXT_texture_filter_anisotropic`.
- [x] All 92 shaders build for both desktop GLSL 4.10 and GLSL ES 3.20 from one
      source. The loader replaces each shader's `#version` line with a prelude
      for the API (`prelude_desktop.glsl`, `prelude_gles.glsl`), which defines
      macros for 1D textures and `noperspective`. Every changed shader compiles
      to the same desktop SPIR-V as upstream's. See [SHADERS.md](SHADERS.md).
- [x] ImGui uses its OpenGL ES 3 backend.

Tools:

- [x] `mise run shaders:check` compiles every shader for both APIs with glslang
      and compares desktop SPIR-V with upstream.
- [x] `mise run render:compare` renders the same frames of each level on the
      desktop build and the adb device, with the shaders that drew each frame.
- [x] `android/scripts/smoke-places.sh` boots the game without a display and
      starts play at continue points over the REPL, for devices that can't
      render.
- [x] Fixed upstream: gk's `--port` was parsed but the listener ignored it, and
      it was a counting flag, so leaving it out meant port 0.

Platform:

- [x] OpenGL ES errors at startup: mip level framebuffers attached level `i` at
      `GL_COLOR_ATTACHMENTi` and drew to buffer 0.
- [x] Every indexed draw uses 32-bit indices and restarts at `UINT32_MAX`, which
      matches OpenGL ES's fixed restart index. The OpenGL ES stand-in warns if
      that ever changes.
- [x] The window always fills the screen: display mode, size and position
      changes only record the setting. `OG_RENDER_SCALE` renders below the
      screen's resolution.
- [x] Audio through cubeb's AAudio backend (its OpenSL ES backend needs Java
      that cubeb only reaches inside Firefox), stopped while the app is in the
      background.
- [x] Back pauses the game instead of closing it, which needs predictive back
      turned off for `targetSdk` 36.
- [x] Backgrounding and resuming: the render thread blocks in SDL, the game
      waits for it, the surface comes back, and there's no ANR.
- [x] The APK ships the repository's runtime files (shaders, assets) and unpacks
      them into the data folder in internal storage. Only `out/<game>` has to be
      installed.
- [x] Log lines have no ANSI colors from the first one.

Launcher and install:

- [x] A launcher in Compose with Material 3 Expressive: the games in a segmented
      list, each one installs from an ISO, plays, installs again or deletes its
      data. System colors on Android 12 and later.
- [x] The extractor ships in the APK as `libextractor.so` and runs as a child
      process, which reads the ISO through a descriptor from the Storage Access
      Framework. It extracts the ISO, checks it against the known copies,
      decompiles and compiles for the device's CPU.
- [x] Installs run in a foreground service with a progress notification, can be
      cancelled, and keep the old install until the new one is done. Errors say
      whether the file isn't an ISO, isn't a known copy of the game, or doesn't
      fit.
- [x] The extractor moves the extracted ISO into place instead of copying it,
      which saved a second copy of the ISO's files.
- [x] The game runs in its own process, since the runtime starts once per
      process.
- [x] CHD disc images (`chdman createdvd` or `createcd`), which emulator users
      keep their games in. libchdr decompresses them as the extractor reads,
      through a `FILE` from `fopencookie` or `funopen`, so the ISO parser is the
      same and no ISO is written. Linux, Android and macOS.

On the AYN Thor (Android 13, Snapdragon 8 Gen 2, Adreno 740, 16 GB):

- [x] Jak 1 installs from the CHD in the Thor's ROM folder in 104 s (75 s of it
      decompressing the CHD), and plays from the launcher: it reaches the title
      screen at 1920x1080.

## Milestone 1: renders on x86_64

Innkeeper and the emulator run the x86-64 object code, so the platform layer and
the renderer can be finished before any arm64 hardware is involved.

- [ ] GL debug output is on, and `render:compare` reports GL errors and warnings
      per place. ANGLE reports none at the 13 places it visits. Adreno reports
      its own driver notes ("Packing allocations for resource") with type
      0x7FFFFFFF, which the log calls errors: filter them.
- [ ] The Thor shows its status bar over the game.
- [ ] `glGetTexImage` is a no-op on OpenGL ES. Only debug texture dumps use it;
      read through a framebuffer with `glReadPixels` instead.
- [ ] The game's own menus still offer display modes, monitors and resolutions,
      which do nothing on Android. Hiding them means changing `goal_src`'s PC
      menus. A render-scale setting needs a place in the app's settings.
- [ ] Pixel-exact render comparisons. Frame numbers don't line up the game state
      between builds, so the comparison shows the same place from slightly
      different moments. Capturing a frame's DMA chain on one build and
      replaying it on both would give identical input. The renderer also loads
      level data asynchronously, which replay would have to wait for.
      `FixedChunkDmaCopier` has an unused `serialize_last_result` to start from.
- [ ] The sky's `noperspective` falls back to perspective interpolation where
      the driver lacks `GL_NV_shader_noperspective_interpolation`. Check the sky
      on Adreno.
- [ ] `--gpu-test` still asks for desktop OpenGL 4.3 in its context attributes.

## Milestone 2: playable on x86_64

- [ ] **Input on hardware.** A virtual Xbox controller works through Innkeeper,
      hotplug included. Check the Thor's built-in controls and their mapping.
      Phones need a touch overlay.
- [ ] **Audio latency** on a device. AAudio opens in low-latency mode with
      240-frame bursts in Innkeeper.
- [ ] **Installs on hardware.** Time and memory on the Thor. The decompiler
      reported 2.2 to 3 GB for Jak 1, and Precursor reports Jak 3 peaking at 7.3
      GB, so check Jak 2 and Jak 3 on 8 GB devices. Android may kill the
      extractor under memory pressure, which shows as a failed install.
- [ ] Jak 2 and Jak 3 install the same way but are untested.
- [ ] Disk use while installing is about 2.5 times the ISO: the extracted files,
      the build's copies of most of them in `out/<game>/iso`, and the converted
      data. Moving instead of copying in the build's `copy-iso-file` would save
      one ISO's worth.
- [ ] The installer has no tests. Its log parsing follows the extractor's log
      lines, which can change upstream.

- [ ] Settings, memory cards and screenshots in app storage, with backup and
      restore.
- [ ] **Performance.** Frame pacing (`ANativeWindow_setFrameRate`, or Swappy),
      120 Hz panels, and thread placement on big.LITTLE cores.
- [ ] Aspect ratios: the Thor's 16:9 top screen and 20:9 phones.
- [x] REPL over `adb forward` works in Innkeeper.

## Milestone 3: arm64

- [x] arm64 game data: `goalc --instruction-set arm64` in a separate checkout
      (README), pushed with `push-data.sh`. The arm64 objects are 11 to 17%
      larger than x86-64's.
- [ ] **The listener never accepts on the translation emulator.** The arm64
      build listens on 8112, but connections time out, even from inside the
      device: the first completes into the backlog of 0 and nothing accepts it.
      `select_and_accept_socket` probably never sees the socket as readable
      under translation. Check on hardware: if it happens there too, it's an
      arm64 bug.
- [ ] **Check the upstream ARM64 backend against the standard ARM64 ABI.**
      Upstream tested it on macOS, and Apple's ABI differs from Linux and
      Android:
  - [x] `x18` is the platform register on both. The backend never allocates it.
  - [x] Apple makes `char` signed, Linux and Android unsigned. Handled with
        `-fsigned-char` (upstream PR #4439 instead changes `kprint`'s argument
        buffer to `s8`).
  - [ ] Apple guarantees a 128-byte red zone below `sp`. Linux and Android
        don't, so a signal can overwrite anything the generated code keeps
        there. Check whether the emitter or `asm_funcs_arm64.s` ever stores
        below `sp`.
  - [ ] Variadic calls pass arguments differently on Apple. Check that no C
        function that GOAL calls is variadic.
  - [ ] Pages are 4 KB or 16 KB on Android, 16 KB on Apple. Check the EE memory
        mapping.
  - [ ] Executable memory: the runtime maps EE memory read, write and execute.
        Android allows that for apps, but check it on a real device, and that
        `__builtin___clear_cache` covers every code write.
- [ ] Heap sizes: arm64 code is bigger. Upstream PR #4439 raised Jak II's
      `DEBUG_LEVEL_HEAP_MULT` to 1.15 for the kid escort mission. Precursor
      found x86-sized backup stacks too small on its own backend. Upstream's
      backend may not have that problem: check.
- [x] Testing arm64 without a handheld: the x86_64 Google APIs emulator image
      translates arm64 apps, including the game code that the runtime links at
      run time. It runs without a display (its GPU stops at OpenGL ES 3.0).
      `qemu-aarch64` user mode with a Linux arm64 `goalc-test` would test the
      backend on the standard ABI without a translator in between, but needs a
      Linux arm64 sysroot.
- [ ] On the Thor itself: performance on the Snapdragon 8 Gen 2 and Adreno 740,
      and Adreno driver bugs (Precursor needed a workaround for bone uniforms on
      Adreno 6xx).

## Milestone 4: an app for players

- [ ] Name, package and icon. The app shouldn't present itself as OpenGOAL,
      which doesn't make or endorse it. The icon is upstream's.
- [ ] More launcher: settings, saves, mods and texture packs. The game list and
      installs are done.
- [ ] The Thor's second screen: show the map, menus or ImGui there through a
      second SDL window or Android's Presentation API.
- [ ] Release signing, CI that builds both ABIs and the APK, and a routine for
      rebasing on upstream.
- [ ] Web requests for the leaderboards: Android's HTTP client through JNI, or
      libcurl with a TLS library that builds for Android.

## Upstreamable

Upstream won't take Android changes, but a few of these are general fixes that a
human could send on their own, without AI co-author trailers, per upstream's
`AI_POLICY.md`: fmt's `<cstdlib>` include, the draco staging race, `-mavx` only
on x86, the Linux arm64 fixes that overlap PR #4439, gk's `--port`, and the
extractor moving its extracted ISO instead of copying it.
