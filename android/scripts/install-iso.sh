#!/usr/bin/env bash
# Install a game on the adb device from an ISO on this machine, as the launcher does after the user
# picks one, but without the file picker. Needs a debug build. The ISO goes to the app's external
# storage, which the app can read. Delete it there when the install is done.
#
#   install-iso.sh <game> <iso>
#
# Innkeeper's adb can write there. On emulators and devices, adb's folders there can be unreadable
# by the app; pick the ISO in the launcher instead.
set -euo pipefail

package="dev.sargunv.pocketgoal"
game="$1"
iso="$2"
dest="/sdcard/Android/data/$package/files/$game.iso"

adb shell mkdir -p "$(dirname "$dest")"
adb push "$iso" "$dest"
adb shell am start -n "$package/.MainActivity" --es install_game "$game" --es install_iso "$dest" >/dev/null
echo "Installing $game. Follow it in the launcher, or with: adb logcat -s OpenGOAL"
