# Android port

An Android build of the OpenGOAL runtime, kept in this fork. Upstream OpenGOAL
doesn't accept Android contributions, so this work stays here and gets rebased
on upstream `master`.

The runtime builds as `libmain.so` for `arm64-v8a` (the target, such as
Snapdragon handhelds) and `x86_64` (emulators and
[Innkeeper](https://github.com/sargunv/innkeeper) on a desktop). The compiler,
decompiler and extractor stay on the desktop and make the game data. `x86_64`
runs the default x86-64 object code. `arm64-v8a` needs the data compiled with
`--instruction-set arm64`.

[ROADMAP.md](ROADMAP.md) lists what works and what's left before the games are
playable.

## Layout

| Path                               | What                                                   |
| ---------------------------------- | ------------------------------------------------------ |
| `android/`                         | Gradle project: the app, `GameActivity`, dev scripts   |
| `game/android/`                    | Android-only native code (stdout and stderr to logcat) |
| `game/graphics/gl.h`               | Picks desktop OpenGL or OpenGL ES (`OG_GLES`)          |
| `game/graphics/gles_compat.h`      | Stand-ins for desktop-only OpenGL calls on OpenGL ES   |
| `third-party/glad-gles/`           | OpenGL ES 3.2 loader                                   |
| `CMakePresets.json`                | `Release-android-arm64` and `Release-android-x86_64`   |
| `third-party/SDL/android-project/` | SDL's Java half, which the app compiles in place       |

## Setup

You need [mise](https://mise.jdx.dev), and an Android SDK in `~/Android/Sdk` (or
`ANDROID_HOME`) with NDK `30.0.16248370` and platform 36. `mise install`
installs CMake, Ninja, NASM, Java and the other tools.

## Develop

Make the game data on the desktop, then build, install and run the app:

```sh
mise run desktop:build
mise run desktop:extract jak1 ~/Games/ps2/jak1.iso
mise run android:install
mise run android:push-data jak1
mise run android:run jak1
mise run android:logcat
```

`mise run android:native` compiles `libmain.so` for both ABIs without Gradle,
which is the fastest way to check that native changes compile.

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
updated. The game data goes in `files/data/out/<game>`: `android:push-data`
copies it in through `/data/local/tmp` and `run-as`, because the app can't read
folders that adb creates in its external storage. Innkeeper has no `run-as`, so
there the data goes to external storage, and `out` links to it.

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

Compile the game for arm64 in a separate checkout (a git worktree), since the
compiler writes to `out/` either way:
`goalc -g jak1 --instruction-set arm64 --proj-path <worktree> -c '(mi)'`, with
the worktree's `iso_data/jak1` and `decompiler_out` linked to the main
checkout's and `out/jak1/fr3` copied. Then run `push-data.sh` from the worktree,
and `android:run --runtime-args --no-display`.

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
