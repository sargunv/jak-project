#!/usr/bin/env bash
# Push one game's data from this checkout to the app on the adb device, instead of installing it
# from its ISO in the app (install-iso.sh). The desktop extractor must have extracted and compiled
# the game first. x86_64 devices run the x86-64 object code that the
# extractor makes by default; arm64 devices need --instruction-set arm64.
#
# The app ships the repository's own files (shaders, assets) in its APK, so only out/<game> goes
# over. On Android, the data goes through /data/local/tmp and `run-as` copies it into the app's
# internal data folder as the app. Innkeeper has no run-as, so the data goes to the app's external
# storage instead, which the app links its data folder's out/ to (GameActivity.kt).
set -euo pipefail

package="dev.sargunv.opengoal"
game="${1:-jak1}"
root="$(git rev-parse --show-toplevel)"
dest="/sdcard/Android/data/$package/files/data/out"

for dir in "out/$game/iso" "out/$game/fr3"; do
  if [[ ! -d "$root/$dir" ]]; then
    echo "missing $dir: extract and compile $game with the desktop extractor first" >&2
    exit 1
  fi
done

if adb shell run-as "$package" true 2>/dev/null; then
  staging="/data/local/tmp/$package-data/$game"
  adb shell rm -rf "$staging"
  adb shell mkdir -p "$staging"
  adb push --sync "$root/out/$game/iso" "$root/out/$game/fr3" "$staging/"
  # out/ may still be the app's link to external storage
  adb shell run-as "$package" sh -c "'cd files && mkdir -p data && { [ ! -L data/out ] || rm data/out; } && mkdir -p data/out && rm -rf data/out/$game && cp -r $staging data/out/'"
  adb shell rm -rf "/data/local/tmp/$package-data"
else
  adb shell mkdir -p "$dest/$game"
  adb push --sync "$root/out/$game/iso" "$root/out/$game/fr3" "$dest/$game/"
fi
