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
- On the AYN Thor (`arm64-v8a`, Adreno 740), Jak 1 installs from its CHD in the
  launcher and plays from the launcher at 60 fps, every frame shown for one
  refresh. Jak 1's opening has been played with the built-in controls.
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
      changes only record the setting. The game's own resolution menu offers the
      screen's size and 75% and 50% of it, which the renderer scales up, as
      borderless mode does on desktop.
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
- [x] Jak II installs from its CHD in 5 min 20 s (peak 4.5 GB, 4.8 GB installed)
      and Jak 3 in 5 min 33 s (peak 5.8 GB, 5.2 GB installed). Both reach their
      title screens and hold 60 fps there. Neither has been played.
- [x] Frame pacing. The Thor's screen runs at 120 Hz (its settings force a
      minimum of 120), and the game's frame limiter put 8% of frames on the
      screen for one or three refreshes instead of two. `GameActivity` now picks
      the display mode for the game's frame rate (`preferredDisplayModeId`,
      which overrides that minimum), and at 60 Hz every frame shows for exactly
      one refresh (`dumpsys SurfaceFlinger --latency`). The game's menus still
      offer frame rates up to the screen's fastest mode, which the runtime asks
      `GameActivity` for (`game/android/activity.cpp`).
- [x] The REPL works on the Thor: goalc connects through `adb forward`, and
      arm64 code that it compiles runs on the device. The listener problem under
      the translation emulator is the emulator's.
- [x] Rumble. The Thor's controller has no motor of its own, so SDL reports no
      rumble for it. Controllers without rumble use the device's vibrator,
      through `GameActivity`, as media: SDL's own vibrations have no usage, so
      Android counts them as touch feedback, which the Thor has turned off.
- [x] The game no longer starts with a black bar over its top 55 px: when the
      window started with the status bar showing, it kept drawing the bar's
      background over the game. `GameActivity` hides the bars before SDL's
      window exists.
- [x] The Adreno 740 has `GL_NV_shader_noperspective_interpolation`, so the sky
      interpolates as on desktop. Skinned characters (merc) render correctly, so
      the Adreno 6xx bone uniform bug that Precursor worked around doesn't
      affect it.

## Milestone 1: renders on x86_64

Innkeeper and the emulator run the x86-64 object code, so the platform layer and
the renderer can be finished before any arm64 hardware is involved.

- [x] GL debug output is on, and `render:compare` reports GL errors and warnings
      per place. ANGLE reports none at the 13 places it visits. Adreno's own
      driver notes ("Packing allocations for resource", type 0x7FFFFFFF) log as
      debug messages instead of errors.
- [x] The game hides the status and navigation bars: the window is created
      fullscreen, and the display manager reads the window's size every frame,
      since hiding the bars resizes the window without an event reaching it. The
      Thor's system UI still draws its own home handle (`SecondaryHomeHandle`)
      over fullscreen apps.
- [x] `glGetTexImage` on OpenGL ES reads level 0 of a 2D texture through a
      framebuffer with `glReadPixels`, for debug texture dumps.
- [x] Jak 1's graphics menu disables display mode and monitor on Android, where
      the game always fills the screen: `pc-get-os` returns `android`. Jak II
      and Jak 3 have their own menus, still to do.
- [ ] Pixel-exact render comparisons. Frame numbers don't line up the game state
      between builds, so the comparison shows the same place from slightly
      different moments. Capturing a frame's DMA chain on one build and
      replaying it on both would give identical input. The renderer also loads
      level data asynchronously, which replay would have to wait for.
      `FixedChunkDmaCopier` has an unused `serialize_last_result` to start from.
- [ ] The sky's `noperspective` falls back to perspective interpolation where
      the driver lacks `GL_NV_shader_noperspective_interpolation`. The Adreno
      740 has it; check the sky on a GPU without it.
- [x] `--gpu-test` asks for the same OpenGL ES 3.2 context as the game.

## Milestone 2: playable on x86_64

- [ ] **Input on hardware.** A virtual Xbox controller works through Innkeeper,
      hotplug included. The Thor's built-in controls play Jak 1. Its Nintendo
      layout mode swaps A with B and X with Y, which the launcher's "Swap face
      buttons" setting undoes (`OG_SWAP_FACE_BUTTONS`, applied before the game's
      position-based binds). Phones need a touch overlay.
- [ ] **Audio latency** on a device. AAudio opens in low-latency mode with
      240-frame bursts in Innkeeper. On the Thor it's a fast mixer track on the
      media stream, not MMAP, and the system volume applies its usual curve.
- [ ] **Install memory.** Jak II's decompile peaked at 7 GB with a thread per
      level, and Android's low memory killer stopped the app on the 16 GB Thor.
      The decompiler config's `level_extract_threads` limits how many levels
      extract at once, and the installer passes 4, which peaks at 5.4 GB on
      desktop. Jak II's build also loaded every DGO again for its `test-zone`
      custom level, to find one texture; on Android, custom levels go without
      textures and art groups from the game. Most of the rest is the decompiler
      holding every DGO's linked words (16 bytes per 4-byte word, 2.4 GB for Jak
      II) and every decoded texture (1.5 GB) at once. 8 GB devices need less:
      pack `LinkedWord` into 12 bytes, or extract levels without loading every
      DGO first. Interning symbol names in `LinkedWord` saved nothing. When
      Android stops an install, the launcher says so the next time it opens.
- [x] Jak II and Jak 3 compile Jak 1's PC kernel (`goal_src/jak1/pc`), which the
      APK now unpacks for them too.
- [x] Disk use while installing: the build hard-links the ISO's files into
      `out/<game>/iso` on Android instead of copying them, which saves one ISO's
      worth. The free space check and the progress bar use a CHD's uncompressed
      size.
- [x] Installer tests (`android:test`, JVM): the log parsing on the extractor's
      log lines, exit statuses, CHD sizes, and a check that the extractor's
      source still logs the lines the parser follows.

- [ ] Settings, memory cards and screenshots in app storage, with backup and
      restore.
- [ ] **Performance.** Thread placement on big.LITTLE cores, and frame times in
      busy levels. Frame pacing at 60 fps is done (see the Thor).
- [ ] Aspect ratios: the Thor's 16:9 top screen and 20:9 phones.
- [x] REPL over `adb forward` works in Innkeeper.

## Milestone 3: arm64

- [x] arm64 game data: `goalc --instruction-set arm64` in a separate checkout
      (README), pushed with `push-data.sh`. The arm64 objects are 11 to 17%
      larger than x86-64's.
- [x] The listener never accepts on the translation emulator: connections time
      out, even from inside the device. On the Thor it works, so it's the
      emulator's translation, not the arm64 build.
- [ ] **Check the upstream ARM64 backend against the standard ARM64 ABI.**
      Upstream tested it on macOS, and Apple's ABI differs from Linux and
      Android:
  - [x] `x18` is the platform register on both. The backend never allocates it.
  - [x] Apple makes `char` signed, Linux and Android unsigned. Handled with
        `-fsigned-char` (upstream PR #4439 instead changes `kprint`'s argument
        buffer to `s8`).
  - [x] Red zone: Apple guarantees 128 bytes below `sp`, Linux and Android
        don't. Neither the emitter nor `asm_funcs_arm64.s` stores below `sp`:
        every push moves `sp` first, and spills are at positive offsets.
  - [x] Variadic calls pass arguments differently on Apple. No C function that
        GOAL calls is variadic: `format` goes through `_stack_call_arm64`, which
        builds its own argument array.
  - [x] Pages: EE memory is mapped without `MAP_FIXED` at a 16 MB aligned hint,
        and the one `mprotect` covers 512 KB, so 4 KB and 16 KB pages both work.
  - [x] Executable memory: the linker, C stubs and mips2c stubs all flush with
        `__builtin___clear_cache` over what they wrote before running it, and
        the Thor runs the game.
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
- [ ] On the Thor itself: frame times in busy levels on the Snapdragon 8 Gen 2.
      The title screen and Geyser Rock hold 60 fps.

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
