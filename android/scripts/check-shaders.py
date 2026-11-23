"""Check the renderer's shaders for desktop OpenGL and OpenGL ES without a GPU.

Each shader goes through the same steps as Shader.cpp: the placeholder substitutions, then the
prelude for each API (prelude_desktop.glsl or prelude_gles.glsl) in place of its #version line.
glslang then compiles it as desktop GLSL and as GLSL ES.

With --base, every shader that differs from that git revision is also compiled to SPIR-V both as
it was at the base (as the upstream loader compiled it) and as it is now (the desktop build).
Identical SPIR-V shows that the change doesn't affect desktop rendering. Changes that do are
listed for review.

Needs glslangValidator, spirv-opt and spirv-dis on PATH (pinned in mise.toml).
"""

import argparse
import difflib
import pathlib
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field

SHADER_DIR = pathlib.Path("game/graphics/opengl_renderer/shaders")
PRELUDES = {"desktop": "prelude_desktop.glsl", "gles": "prelude_gles.glsl"}


def substitute(source: str, stage: str, game: str) -> str:
    """The placeholder substitutions in Shader::Shader."""
    scissor_height = "448.0" if game == "jak1" else "416.0"
    if stage == "vert":
        source = source.replace("HEIGHT_SCALE", "1.0" if game == "jak1" else "0.5")
        source = source.replace("SCISSOR_HEIGHT", scissor_height)
        source = source.replace("SCISSOR_ADJUST", f"(512.0 / {scissor_height})")
    else:
        source = source.replace("SCISSOR_HEIGHT", scissor_height)
    return source


def with_prelude(source: str, prelude: str) -> str:
    """shader_source_for_context in Shader.cpp."""
    version = source.find("#version")
    if version < 0:
        raise ValueError("shader has no #version line")
    line_end = source.find("\n", version)
    rest = "" if line_end < 0 else source[line_end:]
    return prelude + "\n#line 1\n" + source[:version] + rest


def run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, check=False)


def compile_glsl(source: str, stage: str, workdir: pathlib.Path) -> list[str]:
    """Compile with glslang and return its errors."""
    path = workdir / f"shader.{stage}"
    path.write_text(source)
    result = run(["glslangValidator", str(path)])
    if result.returncode == 0:
        return []
    return [
        line.replace(f"{path}:", "")
        for line in result.stdout.splitlines()
        if line.startswith("ERROR:") and "compilation terminated" not in line
    ] or [result.stdout.strip()]


def compile_spirv(source: str, stage: str, workdir: pathlib.Path, name: str) -> bytes | str:
    """Compile desktop GLSL to SPIR-V without debug info. Returns the errors on failure."""
    src = workdir / f"{name}.{stage}"
    spv = workdir / f"{name}.spv"
    stripped = workdir / f"{name}.stripped.spv"
    src.write_text(source)
    result = run(
        [
            "glslangValidator",
            "-G",
            "--auto-map-locations",
            "--auto-map-bindings",
            "-o",
            str(spv),
            str(src),
        ]
    )
    if result.returncode != 0:
        return result.stdout.strip()
    result = run(["spirv-opt", "--strip-debug", str(spv), "-o", str(stripped)])
    if result.returncode != 0:
        return result.stderr.strip()
    return stripped.read_bytes()


def disassemble(binary: bytes, workdir: pathlib.Path) -> list[str]:
    path = workdir / "dis.spv"
    path.write_bytes(binary)
    return run(["spirv-dis", "--raw-id", str(path)]).stdout.splitlines()


def git_show(rev: str, path: pathlib.Path) -> str | None:
    result = run(["git", "show", f"{rev}:{path.as_posix()}"])
    return result.stdout if result.returncode == 0 else None


def default_base() -> str | None:
    result = run(["git", "merge-base", "HEAD", "upstream/master"])
    return result.stdout.strip() if result.returncode == 0 else None


@dataclass
class Report:
    errors: dict[str, list[str]] = field(default_factory=dict)
    spirv_same: list[str] = field(default_factory=list)
    spirv_changed: dict[str, list[str]] = field(default_factory=dict)


def check(
    shaders: list[pathlib.Path], game: str, base: str | None, workdir: pathlib.Path
) -> Report:
    report = Report()
    preludes = {api: (SHADER_DIR / name).read_text() for api, name in PRELUDES.items()}
    for path in shaders:
        stage = path.suffix[1:]
        source = substitute(path.read_text(), stage, game)
        for api, prelude in preludes.items():
            errors = compile_glsl(with_prelude(source, prelude), stage, workdir)
            if errors:
                report.errors[f"{path.name} ({api})"] = errors

        if base is None:
            continue
        base_text = git_show(base, path)
        if base_text == path.read_text():
            continue
        if base_text is None:
            report.spirv_changed[path.name] = ["new shader"]
            continue
        before = compile_spirv(substitute(base_text, stage, game), stage, workdir, "before")
        after = compile_spirv(with_prelude(source, preludes["desktop"]), stage, workdir, "after")
        if isinstance(before, str) or isinstance(after, str):
            failure = before if isinstance(before, str) else after
            report.spirv_changed[path.name] = [f"SPIR-V compile failed: {failure}"]
        elif before == after:
            report.spirv_same.append(path.name)
        else:
            diff = difflib.unified_diff(
                disassemble(before, workdir), disassemble(after, workdir), lineterm="", n=1
            )
            report.spirv_changed[path.name] = list(diff)[2:]
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("shaders", nargs="*", type=pathlib.Path, help="Default: every shader")
    parser.add_argument("--game", choices=["jak1", "jak2"], default="jak1")
    parser.add_argument(
        "--base",
        help="Compare SPIR-V against this git revision. Default: the merge base with upstream.",
    )
    parser.add_argument("--no-base", action="store_true", help="Skip the SPIR-V comparison")
    parser.add_argument("--diff", action="store_true", help="Show SPIR-V diffs of changed shaders")
    parser.add_argument(
        "--strict", action="store_true", help="Fail when a change affects desktop SPIR-V"
    )
    args = parser.parse_args()

    shaders = args.shaders or sorted(
        p for p in SHADER_DIR.iterdir() if p.suffix in (".vert", ".frag")
    )
    base = None if args.no_base else args.base or default_base()

    with tempfile.TemporaryDirectory() as tmp:
        report = check(shaders, args.game, base, pathlib.Path(tmp))

    for name, errors in report.errors.items():
        print(f"FAIL {name}")
        for error in errors:
            print(f"     {error}")
    if base:
        for name, diff in report.spirv_changed.items():
            print(f"DESKTOP CHANGED {name}")
            for line in diff if args.diff else diff[:1]:
                print(f"     {line}")
    print(
        f"{len(shaders)} shaders, {len(report.errors)} failed compiles",
        end="",
    )
    if base:
        print(
            f", desktop SPIR-V vs {base[:9]}: {len(report.spirv_same)} identical,"
            f" {len(report.spirv_changed)} changed",
            end="",
        )
    print()
    failed = bool(report.errors) or (args.strict and bool(report.spirv_changed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
