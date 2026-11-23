#!/usr/bin/env bash
# Smoke-test the game on the adb device without a display: boot it in debug mode, start play at
# each continue point over a goalc REPL, and report whether the level was entered and the game is
# still running. For devices that can't render, such as the arm64 translation emulator.
#
#   smoke-places.sh [--instruction-set arm64] [--proj-path DIR] [--out DIR] place...
#
# --proj-path is the checkout whose out/ holds the game data on the device (for arm64, the
# worktree that compiled it), so the REPL's code matches.
set -uo pipefail

instruction_set=x86
proj_path="$(git rev-parse --show-toplevel)"
out="$proj_path/out/smoke-places"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --instruction-set) instruction_set="$2"; shift 2 ;;
    --proj-path) proj_path="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) break ;;
  esac
done
goalc="$(git rev-parse --show-toplevel)/build/Release/bin/goalc/goalc"
package=dev.sargunv.opengoal
mkdir -p "$out"
adb forward tcp:8132 tcp:8112 >/dev/null

for place in "$@"; do
  adb shell am force-stop "$package"
  adb logcat -c
  adb shell "am start -n $package/.GameActivity --es game jak1 --es args '-boot -fakeiso -debug' --es runtime_args '--no-display'" >/dev/null
  sleep 25
  # goalc resets the game when it exits normally, so keep it running and kill it afterwards
  (
    printf '(mi)\n(lt "127.0.0.1" 8132)\n(start (quote play) (get-continue-by-name *game-info* "%s"))\n' "$place"
    sleep 300
  ) | (cd "$proj_path" && "$goalc" -g jak1 --instruction-set "$instruction_set" --proj-path "$proj_path" --disable-ansi) \
    >"$out/$place.goalc.log" 2>&1 &
  repl=$!
  sleep 75
  pid=$(adb shell pidof "$package")
  adb logcat -d -s OpenGOAL:V DEBUG:V libc:F AndroidRuntime:E >"$out/$place.log"
  pkill -9 -P "$repl" 2>/dev/null
  kill -9 "$repl" 2>/dev/null
  wait "$repl" 2>/dev/null
  entered=$(grep -o "GAMEPLAY: enter [a-z0-9-]*" "$out/$place.log" | sort -u | sed 's/GAMEPLAY: enter //' | tr '\n' ' ')
  fatal=$(grep -cE "Fatal signal|FATAL EXCEPTION|\[die\]" "$out/$place.log")
  connected=$(grep -c "Got version" "$out/$place.goalc.log")
  echo "$place: running=${pid:+yes} repl=$connected fatal=$fatal entered: $entered"
done
adb shell am force-stop "$package"
