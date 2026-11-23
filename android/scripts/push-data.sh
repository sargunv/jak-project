#!/usr/bin/env bash
# Push one game's data from this checkout to the app's external storage on the adb device. The
# desktop extractor must have extracted and compiled the game first. x86_64 devices run the x86-64
# object code that the extractor makes by default.
set -euo pipefail

game="${1:-jak1}"
root="$(git rev-parse --show-toplevel)"
dest="/sdcard/Android/data/dev.sargunv.opengoal/files/data"

for dir in "out/$game/iso" "out/$game/fr3"; do
  if [[ ! -d "$root/$dir" ]]; then
    echo "missing $dir: extract and compile $game with the desktop extractor first" >&2
    exit 1
  fi
done

adb shell mkdir -p "$dest/decompiler" "$dest/game/graphics/opengl_renderer" "$dest/out/$game"
adb push --sync "$root/decompiler/config" "$dest/decompiler/"
adb push --sync "$root/game/assets" "$dest/game/"
adb push --sync "$root/game/graphics/opengl_renderer/shaders" "$dest/game/graphics/opengl_renderer/"
adb push --sync "$root/custom_assets" "$root/goal_src" "$dest/"
adb push --sync "$root/out/$game/iso" "$root/out/$game/fr3" "$dest/out/$game/"
