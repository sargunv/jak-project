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

`android:run` takes the game's arguments with `--args`, such as
`--args "-boot -fakeiso -debug"`. Without `-boot`, the game waits for the REPL.
The REPL should reach it through `adb forward tcp:8112 tcp:8112` (8113 for Jak
II), but nobody has tried that yet.

The app reads the game data from its external storage,
`/sdcard/Android/data/dev.sargunv.opengoal/files/data`, so `adb push` can update
it. Logs also go to `data/log/` there.

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
