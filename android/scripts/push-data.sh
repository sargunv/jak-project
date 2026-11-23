#!/usr/bin/env bash
# Push one game's data from this checkout to the app's external storage on the adb device. The
# desktop extractor must have extracted and compiled the game first. x86_64 devices run the x86-64
# object code that the extractor makes by default.
#
# The app unpacks the repository's own files (shaders, assets) from its APK. --files pushes them
# too, to try shader or asset changes without rebuilding the APK. The app replaces them again
# when it's updated.
set -euo pipefail

files=false
if [[ "${1:-}" == "--files" ]]; then
  files=true
  shift
fi
game="${1:-jak1}"
root="$(git rev-parse --show-toplevel)"
dest="/sdcard/Android/data/dev.sargunv.opengoal/files/data"

for dir in "out/$game/iso" "out/$game/fr3"; do
  if [[ ! -d "$root/$dir" ]]; then
    echo "missing $dir: extract and compile $game with the desktop extractor first" >&2
    exit 1
  fi
done

adb shell mkdir -p "$dest/out/$game"
adb push --sync "$root/out/$game/iso" "$root/out/$game/fr3" "$dest/out/$game/"

if $files; then
  adb shell mkdir -p "$dest/game/graphics/opengl_renderer"
  adb push --sync "$root/game/assets" "$dest/game/"
  adb push --sync "$root/game/graphics/opengl_renderer/shaders" "$dest/game/graphics/opengl_renderer/"
  adb push --sync "$root/custom_assets" "$dest/"
fi
