# Android port

An Android build of the OpenGOAL runtime, kept in this fork. Upstream OpenGOAL
doesn't accept Android contributions, so this work stays here and gets rebased
on upstream `master`.

The app builds for `arm64-v8a` (the target, such as Snapdragon handhelds) and
`x86_64` (emulators and [Innkeeper](https://github.com/sargunv/innkeeper) on a
desktop). It has a launcher that lists the games and installs each one from an
ISO of the player's disc: the APK ships the extractor, which extracts,
decompiles and compiles the game on the device, for the device's CPU. The game
runtime builds as `libmain.so`.

[ROADMAP.md](ROADMAP.md) lists what works and what's left before the games are
playable.

## Layout

| Path                               | What                                                 |
| ---------------------------------- | ---------------------------------------------------- |
| `android/`                         | Gradle project: the app, its launcher, dev scripts   |
| `game/android/`                    | Android-only native code: logcat, starting a process |
| `game/graphics/gl.h`               | Picks desktop OpenGL or OpenGL ES (`OG_GLES`)        |
| `game/graphics/gles_compat.h`      | Stand-ins for desktop-only OpenGL calls on OpenGL ES |
| `third-party/glad-gles/`           | OpenGL ES 3.2 loader                                 |
| `CMakePresets.json`                | `Release-android-arm64` and `Release-android-x86_64` |
| `third-party/SDL/android-project/` | SDL's Java half, which the app compiles in place     |

## Setup

You need [mise](https://mise.jdx.dev), and an Android SDK in `~/Android/Sdk` (or
`ANDROID_HOME`) with NDK `30.0.16248370` and platform 37.2. `mise install`
installs CMake, Ninja, NASM, Java and the other tools.

## Develop

Build and install the app, install a game from its ISO, and play it:

```sh
mise run android:install
mise run android:install-iso jak1 ~/Games/ps2/jak1.iso
mise run android:run jak1
mise run android:logcat
```

`android:install-iso` does what the launcher does after the player picks an ISO,
without the file picker, in debug builds. Picking the ISO in the launcher works
too, except in Innkeeper, whose picker is the desktop's. `android:push-data`
instead copies game data that the desktop extractor made
(`mise run desktop:build`, then `mise run desktop:extract jak1 <iso>`).

`mise run android:native` compiles the native code for both ABIs without Gradle,
which is the fastest way to check that native changes compile.

### Installing from an ISO

The launcher (`MainActivity`) gets the ISO from the Storage Access Framework,
which gives the app a file descriptor and no path. `InstallService` runs in the
foreground with a notification, and `Installer` runs the extractor as a child
process: Android only installs native code named `lib*.so`, so the APK ships the
extractor executable as `libextractor.so`, and the app runs it from its native
library folder. `libog_process.so` starts it with the ISO's descriptor as its
descriptor 3, and the extractor reads `/proc/self/fd/3` through a copy of that
descriptor (`file_util::open_file`).

The extractor works in `files/install/` with the files it needs from the APK
(`goal_src/`, the decompiler's config, the assets), which takes about 2.5 times
the ISO's size while it runs. Only `out/<game>/iso` and `out/<game>/fr3` move
into the runtime's data folder, and the rest is deleted. Jak 1 takes about 30
seconds in Innkeeper and 50 under the emulator's ARM translation, and ends up
1.6 GB.

`GameActivity` runs in its own process (`:game`), since the runtime can only
start once per process, and that process ends with the game.

### Graphics

- `mise run shaders:check` compiles every shader for desktop OpenGL and OpenGL
  ES in a few seconds, without a GPU, and shows whether a shader change affects
  desktop rendering. [SHADERS.md](SHADERS.md) explains the porting rules.
- `mise run render:compare` boots each level on the desktop build (no window)
  and the adb device, takes screenshots of the same frames, and writes
  `out/render-compare/report.md` with side-by-side sheets and the shaders that
  drew each frame. `--places training-start,village1-hut` picks continue points.
  It needs `mise run desktop:build` and current data on the device.
- `OG_SCREENSHOT_FRAMES=600,1200` makes either build save screenshots of those
  game frames (`android:run --env` passes it on Android).

`android:run` takes the game's arguments with `--args`, such as
`--args "-boot -fakeiso -debug"`, runtime options with `--runtime-args`, such as
`--no-display`, and environment variables with `--env`. Without `-boot`, the
game waits for the REPL. The REPL reaches a device through
`adb forward tcp:8132 tcp:8112` and `(lt "127.0.0.1" 8132)`. For an arm64
device, start goalc with `--instruction-set arm64`.

### Storage

The runtime's data folder is in the app's internal storage (`files/data`). The
app unpacks the files it ships there, such as shaders, when it's installed or
updated. Each game's data goes in `files/data/out/<game>`, where the installer
puts it. `android:push-data` copies a desktop's data there through
`/data/local/tmp` and `run-as`, because the app can't read folders that adb
creates in its external storage. Innkeeper has no `run-as`, so there the data
goes to external storage, and debug builds link `out` to it.

### arm64 without hardware

The x86_64 Google APIs emulator images translate arm64 apps to x86-64
(`libndk_translation`). That runs the arm64 build, including the game code that
the runtime links at run time, but its GPU only offers OpenGL ES 3.0, so run it
without a display:

```sh
avdmanager create avd -n opengoal-arm-translation -d medium_tablet \
  -k "system-images;android-36;google_apis;x86_64"
emulator -avd opengoal-arm-translation -port 5560 -no-window -no-audio -gpu host
adb -s emulator-5560 install --abi arm64-v8a -r android/app/build/outputs/apk/debug/app-debug.apk
```

Install the game in the launcher, which runs the arm64 extractor and compiles
arm64 code, then `android:run --runtime-args --no-display`. Its file picker is
Android's own: push the ISO to `/sdcard/Download` first. For the REPL, goalc
needs `--instruction-set arm64` and arm64 objects in its `out/`, so compile them
in a separate checkout (a git worktree) with
`goalc -g jak1 --instruction-set arm64 --proj-path <worktree> -c '(mi)'`.

### Innkeeper

Innkeeper runs the `x86_64` build on this desktop's GPU, through ANGLE on
Vulkan. Use a profile of its own with adb on:

```sh
flatpak run dev.sargunv.Innkeeper profile create opengoal
flatpak run dev.sargunv.Innkeeper --profile opengoal profile set --adb on --display medium-tablet --idle-minutes 120
flatpak run dev.sargunv.Innkeeper --profile opengoal profile start
```

`profile list` shows the profile's adb serial. Set `ANDROID_SERIAL` to it when
more than one device is connected.

## Rules

- Never commit game data. `iso_data/`, `out/` and `decompiler_out/` are ignored.
  Keep ISOs outside the repository. The extractor deletes
  `iso_data/<game>/.gitignore` when it extracts again, so restore it with
  `git checkout -- iso_data`.
- Keep Android changes behind `ANDROID`, `__ANDROID__` or `OG_GLES`, so the
  desktop build stays the same and rebases on upstream stay small.
