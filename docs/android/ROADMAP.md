# Android roadmap

What works, and what's left before Jak 1 is playable on an arm64 Android
handheld such as the AYN Thor. Jak 2 and Jak 3 come after Jak 1.

## Status

- `libmain.so` builds for `arm64-v8a` and `x86_64` with NDK r30. The APK
  packages both. Pages are 16 KB aligned.
- On `x86_64` in Innkeeper, Jak 1 renders: the title screen and every level the
  render comparison visits look the same as the desktop build. Nothing has been
  played yet: input, audio and the rest of milestone 2 are next.
- The desktop build still builds, and `goalc-test` passes (1669 tests).
- Nothing has run on `arm64-v8a` yet.

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
- [x] Fixed upstream: gk's `--port` was parsed but the listener ignored it.

## Milestone 1: renders on x86_64

Innkeeper and the emulator run the x86-64 object code, so the platform layer and
the renderer can be finished before any arm64 hardware is involved.

- [ ] **`glDrawBuffers` errors at startup.** OpenGL ES requires entry `i` to be
      `GL_COLOR_ATTACHMENTi` or `GL_NONE`, and ANGLE reports
      `GL_INVALID_OPERATION` 7 times at startup. Find the call and reorder its
      attachments on OpenGL ES.
- [ ] **Turn on GL debug output** (`KHR_debug` is core in OpenGL ES 3.2) and fix
      what it reports at runtime. Likely: unsized or invalid internal formats in
      `glTexImage2D` (OpenGL ES only accepts some format and type pairs),
      framebuffer formats that can't be rendered to, `glBlitFramebuffer` resolve
      rules (OpenGL ES requires matching formats and sizes), and depth formats.
- [ ] Check primitive restart. OpenGL ES restarts at `0xFFFF` for 16-bit
      indices, where desktop restarts at `UINT32_MAX`. Make sure every restart
      draw uses 32-bit indices.
- [ ] `glGetTexImage` is a no-op on OpenGL ES. Only debug texture dumps use it;
      read through a framebuffer with `glReadPixels` instead.
- [ ] The display manager lists monitors and fullscreen modes. On Android
      there's one fullscreen window, so hide those settings and add a
      render-scale setting.
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
- [ ] Log colors before `--disable-ansi` takes effect: the first few log lines
      still have ANSI escapes.

## Milestone 2: playable on x86_64

- [ ] **Input.** SDL's Android gamepad support should hand the Thor's built-in
      controls to the game as a gamepad. Check the mapping, and that Back and
      Select+Start reach the pause menu (`SDL_ANDROID_TRAP_BACK_BUTTON`). Phones
      need a touch overlay.
- [ ] **Audio.** cubeb builds with its AAudio and OpenSL ES backends, but logs
      `Cubeb init failed` in Innkeeper (the desktop's offscreen runs log it too,
      with no audio device). Check playback on the emulator and a device,
      latency, and that sound pauses in the background.
- [ ] **Lifecycle.** When the app goes to the background, the EE and IOP threads
      have to pause with SDL, the EGL surface comes back on resume, and settings
      and saves have to be written first. Check that a long pause doesn't cause
      an ANR.
- [ ] **Game data install.** `adb push` is a development stand-in. Pick between:
  - Make the data on a desktop and copy it over. Simple, but awkward for
    players.
  - Run the extractor and compiler on the device, like Precursor does. It needs
    `goalc` and the extractor built for Android, and a lot of memory: Precursor
    reports Jak 3 peaking at 7.3 GB.

  Either way, the app should unpack the files it ships (shaders, `game/assets`,
  `custom_assets`, `goal_src`) from its APK assets, and pick ISOs and data with
  the Storage Access Framework.

- [ ] Settings, memory cards and screenshots in app storage, with backup and
      restore.
- [ ] **Performance.** Frame pacing (`ANativeWindow_setFrameRate`, or Swappy),
      120 Hz panels, and thread placement on big.LITTLE cores.
- [ ] Aspect ratios: the Thor's 16:9 top screen and 20:9 phones.
- [ ] REPL over `adb forward tcp:8112 tcp:8112` for development. It should work
      already: check it.

## Milestone 3: arm64

- [ ] Compile the game data with `extractor --instruction-set arm64` (or
      `mise run desktop:extract jak1 <iso> --instruction-set arm64`), and push
      that to arm64 devices. `push-data.sh` assumes x86-64 data today.
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
- [ ] Ways to test arm64 without a handheld:
  - `qemu-aarch64` user mode: run a Linux arm64 build of `goalc-test`, which
    starts the runtime without a display, to test the backend on the standard
    ARM64 ABI. Fast, and covers the riskiest part.
  - The arm64 Android 36 system image in the emulator. Full-system emulation on
    an x86 host is slow, but enough to check that the game boots and links its
    code.
  - An x86_64 Google APIs image with ARM translation for apps. Unknown whether
    the translator handles code that the game writes and runs at runtime.
- [ ] On the Thor itself: performance on the Snapdragon 8 Gen 2 and Adreno 740,
      and Adreno driver bugs (Precursor needed a workaround for bone uniforms on
      Adreno 6xx).

## Milestone 4: an app for players

- [ ] Name, package and icon. The app shouldn't present itself as OpenGOAL,
      which doesn't make or endorse it. The icon is upstream's.
- [ ] A launcher: game list, data install progress, settings, saves, mods and
      texture packs.
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
on x86, and the Linux arm64 fixes that overlap PR #4439.
