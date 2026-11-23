"""Render the same game frames on the desktop build and on an Android device, and compare them.

For each place, both builds boot in debug mode, and a goalc REPL connected to each one starts play
at that continue point. Both save screenshots of the same game frames at the same internal
resolution, each with the list of shaders that drew it (OG_SCREENSHOT_FRAMES and
OG_SCREENSHOT_DIR in game/graphics/pipelines/opengl.cpp). The desktop build runs without a window,
through SDL's offscreen video driver. The output directory gets the screenshots, a side-by-side
sheet per frame (desktop, Android, difference), and report.md.

The game doesn't render deterministically by frame number: the REPL command lands on a different
frame in each build, and NPCs, particles and loading run on their own timing. Expect differences
in anything that moves. Look for missing or miscoloured layers.

goalc resets its target when it exits normally, so the REPLs are killed instead. Needs Pillow:
`mise run render:compare` runs this through uv with it.
"""

import argparse
import json
import os
import pathlib
import shlex
import signal
import subprocess
import sys
import tempfile
import time

from PIL import Image, ImageChops, ImageStat

PACKAGE = "dev.sargunv.opengoal"
DEVICE_DIR = f"/sdcard/Android/data/{PACKAGE}/files/render-compare"
GAME_ARGS = ["-boot", "-fakeiso", "-debug"]
DEVICE_PORT = 8112  # Jak 1's default listener port, used on the device
DESKTOP_PORT = 8122  # Innkeeper shares the host's network, so the desktop game can't take 8112
FORWARDED_PORT = 8132  # host port that reaches the device's listener through adb forward
# Continue points from goal_src/jak1/engine/level/level-info.gc
JAK1_PLACES = [
    "training-start",
    "village1-hut",
    "beach-start",
    "jungle-start",
    "misty-start",
    "firecanyon-start",
    "village2-start",
    "sunken-start",
    "swamp-start",
    "snow-start",
    "maincave-start",
    "lavatube-start",
    "citadel-start",
]


def wait_for(done, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if done():
            return True
        time.sleep(1)
    return False


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def start_desktop(gk: pathlib.Path, game: str, frames: str, out: pathlib.Path):
    out.mkdir(parents=True, exist_ok=True)
    config = tempfile.mkdtemp(prefix="render-compare-")
    env = dict(os.environ, SDL_VIDEO_DRIVER="offscreen")
    env.update(OG_SCREENSHOT_FRAMES=frames, OG_SCREENSHOT_DIR=str(out))
    cmd = [str(gk), "--disable-ansi", "--game", game, f"--port={DESKTOP_PORT}"]
    cmd += ["--proj-path", os.getcwd()]
    cmd += ["--config-path", config, "--", *GAME_ARGS]
    with open(out / "gk.log", "w") as log:
        # the child keeps its own copy of the log's file descriptor
        return subprocess.Popen(cmd, env=env, stdout=log, stderr=subprocess.STDOUT)


def start_android(game: str, frames: str, device_out: str) -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "rm", "-rf", device_out, check=False)
    adb("forward", f"tcp:{FORWARDED_PORT}", f"tcp:{DEVICE_PORT}")
    env = f"OG_SCREENSHOT_FRAMES={frames} OG_SCREENSHOT_DIR={device_out}"
    # adb shell joins its arguments into one device shell command, so quote each value
    adb(
        "shell",
        f"am start -n {PACKAGE}/.GameActivity --es game {game}"
        f" --es args {shlex.quote(' '.join(GAME_ARGS))} --es env {shlex.quote(env)}",
    )


def start_play(goalc: pathlib.Path, game: str, port: int, place: str, log_path: pathlib.Path):
    """Start a goalc REPL that connects to the game and starts play at a continue point.

    The REPL keeps running so that it doesn't reset the game; kill it when done.
    """
    commands = [
        "(mi)",  # loads the game's types, so the start command compiles
        f'(lt "127.0.0.1" {port})',
        f'(start \'play (get-continue-by-name *game-info* "{place}"))',
    ]
    log = open(log_path, "w")  # noqa: SIM115 - the REPL outlives this function
    repl = subprocess.Popen(
        [str(goalc), "-g", game, "--proj-path", os.getcwd(), "--disable-ansi"],
        stdin=subprocess.PIPE,
        stdout=log,
        stderr=subprocess.STDOUT,
        text=True,
    )
    assert repl.stdin is not None
    repl.stdin.write("\n".join(commands) + "\n")
    repl.stdin.flush()
    return repl


def kill(process: subprocess.Popen | None) -> None:
    if process and process.poll() is None:
        process.send_signal(signal.SIGKILL)
        process.wait()


def compare(desktop: pathlib.Path, android: pathlib.Path, sheet_path: pathlib.Path) -> float:
    a = Image.open(desktop).convert("RGB")
    b = Image.open(android).convert("RGB")
    if a.size != b.size:
        b = b.resize(a.size)
    diff = ImageChops.difference(a, b)
    mean = sum(ImageStat.Stat(diff).mean) / 3
    w, h = a.width // 2, a.height // 2
    sheet = Image.new("RGB", (w * 3, h))
    sheet.paste(a.resize((w, h)), (0, 0))
    sheet.paste(b.resize((w, h)), (w, 0))
    sheet.paste(diff.point(lambda v: min(255, v * 4)).resize((w, h)), (2 * w, 0))
    sheet.save(sheet_path)
    return mean


def shaders(path: pathlib.Path) -> dict[str, int]:
    return json.loads(path.read_text()) if path.exists() else {}


def capture(args, place: str, frames: str, last: str) -> tuple[bool, bool | None]:
    """Run one place on both builds. Returns whether each reached the last frame."""
    out = args.out / place
    device_out = f"{DEVICE_DIR}/{place}"
    desktop = start_desktop(args.gk, args.game, frames, out / "desktop")
    if not args.desktop_only:
        start_android(args.game, frames, device_out)
    repls = []
    try:
        time.sleep(args.boot_wait)
        repls.append(
            start_play(args.goalc, args.game, DESKTOP_PORT, place, out / "goalc-desktop.log")
        )
        if not args.desktop_only:
            # one (mi) at a time, since both REPLs build into the same out/ folder
            connected = out / "goalc-desktop.log"
            wait_for(lambda: "Got version" in connected.read_text(), 60)
            repls.append(
                start_play(args.goalc, args.game, FORWARDED_PORT, place, out / "goalc-android.log")
            )

        desktop_done = wait_for((out / "desktop" / last).exists, args.timeout)
        android_done = None
        if not args.desktop_only:
            android_done = wait_for(
                lambda: last in adb("shell", "ls", device_out, check=False), args.timeout
            )
            (out / "android").mkdir(parents=True, exist_ok=True)
            adb("pull", f"{device_out}/.", str(out / "android"), check=False)
        return desktop_done, android_done
    finally:
        for repl in repls:
            kill(repl)
        kill(desktop)
        if not args.desktop_only:
            adb("shell", "am", "force-stop", PACKAGE, check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game", default="jak1")
    parser.add_argument(
        "--places", default=",".join(JAK1_PLACES), help="Continue points, comma-separated"
    )
    parser.add_argument("--frames", default="2700,3600", help="Game frames to capture")
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("out/render-compare"))
    parser.add_argument(
        "--gk", type=pathlib.Path, default=pathlib.Path("build/Release/bin/game/gk")
    )
    parser.add_argument(
        "--goalc", type=pathlib.Path, default=pathlib.Path("build/Release/bin/goalc/goalc")
    )
    parser.add_argument("--desktop-only", action="store_true")
    parser.add_argument("--boot-wait", type=float, default=15, help="Seconds before the REPL")
    parser.add_argument("--timeout", type=float, default=180, help="Seconds per place and build")
    args = parser.parse_args()

    frame_names = [f"frame-{int(f):05}" for f in args.frames.split(",")]
    last = frame_names[-1] + ".shaders.json"
    report = ["# Render comparison", "", "Desktop OpenGL (left) and Android (middle)."]
    report += ["Difference on the right, brightened 4x.", ""]
    all_shaders: dict[str, list[str]] = {}

    for place in args.places.split(","):
        print(f"{place}: running", flush=True)
        desktop_done, android_done = capture(args, place, args.frames, last)
        out = args.out / place

        report += [f"## {place}", ""]
        if not desktop_done:
            report += ["Desktop didn't reach the last frame. See `desktop/gk.log`.", ""]
        if android_done is False:
            report += ["Android didn't reach the last frame. Check `adb logcat`.", ""]
        for name in frame_names:
            d_png, a_png = out / "desktop" / f"{name}.png", out / "android" / f"{name}.png"
            d_sh = shaders(out / "desktop" / f"{name}.shaders.json")
            a_sh = shaders(out / "android" / f"{name}.shaders.json")
            for shader in d_sh:
                all_shaders.setdefault(shader, [])
                if place not in all_shaders[shader]:
                    all_shaders[shader].append(place)
            line = f"- **{name}**"
            if d_png.exists() and a_png.exists():
                mean = compare(d_png, a_png, out / f"{name}.compare.png")
                line += f": mean difference {mean:.1f}/255, [sheet]({place}/{name}.compare.png)"
            report.append(line)
            report.append(f"  - shaders: {', '.join(sorted(d_sh)) or 'none captured'}")
            if a_sh and set(a_sh) != set(d_sh):
                only_d = sorted(set(d_sh) - set(a_sh))
                only_a = sorted(set(a_sh) - set(d_sh))
                report.append(f"  - only desktop: {only_d}, only Android: {only_a}")
        report.append("")
        android = (
            "" if android_done is None else f", android {'ok' if android_done else 'incomplete'}"
        )
        print(f"{place}: desktop {'ok' if desktop_done else 'incomplete'}{android}", flush=True)

    report += ["## Shaders by place", ""]
    for shader in sorted(all_shaders):
        report.append(f"- `{shader}`: {', '.join(all_shaders[shader])}")
    (args.out / "report.md").write_text("\n".join(report) + "\n")
    print(f"wrote {args.out / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
