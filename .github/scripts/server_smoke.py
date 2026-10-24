#!/usr/bin/env python3
"""Starts a real dedicated server with the built DreamDisplays jar and fails on any load error.

    server_smoke.py fabric --mc 1.21.11 --loader 0.19.3 --api 0.141.4+1.21.11 --jar dreamdisplays-fabric.jar
    server_smoke.py quilt  --mc 1.21.11 --api 0.141.4+1.21.11 --jar dreamdisplays-fabric.jar
    server_smoke.py paper  --mc 1.21.11 --jar dreamdisplays-paper.jar

The server boots a flat world, is stopped as soon as it reports "Done", and its log is then checked
for mixin failures, linkage errors and DreamDisplays exceptions. A one-line result is appended to the
GitHub step summary. Exit code 0 means the server started cleanly with the mod or plugin loaded.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

USER_AGENT = "dreamdisplays-ci (https://github.com/xGRAZx/dreamdisplays)"
BOOT_TIMEOUT_S = 900

# Any of these anywhere in the log fails the run: they mean code that did not link or a mixin that
# did not apply, which would crash or silently break players' games.
FATAL_MARKERS = (
    "Mixin apply failed",
    "Mixin apply for mod dreamdisplays failed",
    "MixinApplyError",
    "InvalidMixinException",
    "InjectionError",
    "Incompatible mods found",
    "Incompatible mod set",
    "Could not execute entrypoint",
    "NoClassDefFoundError",
    "NoSuchMethodError",
    "NoSuchFieldError",
    "VerifyError",
    "ClassNotFoundException",
    "IllegalAccessError",
    "AbstractMethodError",
    "IncompatibleClassChangeError",
    "Error occurred while enabling",
    "Could not load plugin",
    "Exception in thread",
)
OWN_ERROR = re.compile(r"(ERROR|Exception|Error:).*dreamdisplays|dreamdisplays.*(ERROR|Exception)", re.IGNORECASE)


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - retried, then re-raised
            if attempt == 3:
                raise
            print(f"fetch {url} failed ({error}), retrying", flush=True)
            time.sleep(2 ** (attempt + 1))
    raise AssertionError("unreachable")


def download(url: str, target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(fetch(url))
    return target


def fetch_json(url: str):
    return json.loads(fetch(url))


def relax_fabric_loader(jar: Path, target: Path) -> Path:
    """Copies [jar] with its fabricloader dependency opened up, to probe older loaders."""
    with zipfile.ZipFile(jar) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
        for item in source.infolist():
            data = source.read(item.filename)
            if item.filename == "fabric.mod.json":
                meta = json.loads(data)
                meta.setdefault("depends", {})["fabricloader"] = "*"
                data = json.dumps(meta, indent=2).encode()
            out.writestr(item, data)
    return target


def drop_quilt_metadata(jar: Path, target: Path) -> Path:
    """Copies [jar] without quilt.mod.json, so Quilt reads fabric.mod.json instead."""
    with zipfile.ZipFile(jar) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
        for item in source.infolist():
            if item.filename != "quilt.mod.json":
                out.writestr(item, source.read(item.filename))
    return target


def write_server_files(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / "eula.txt").write_text("eula=true\n")
    (root / "server.properties").write_text(
        "online-mode=false\n"
        "level-type=minecraft\\:flat\n"
        "generate-structures=false\n"
        "spawn-protection=0\n"
        "view-distance=3\n"
        "simulation-distance=3\n"
        "server-port=25565\n"
        "sync-chunk-writes=false\n"
    )


def fabric_installer_version() -> str:
    return next(v["version"] for v in fetch_json("https://meta.fabricmc.net/v2/versions/installer") if v["stable"])


def prepare_fabric(args, root: Path) -> list[str]:
    write_server_files(root)
    installer = fabric_installer_version()
    download(
        f"https://meta.fabricmc.net/v2/versions/loader/{args.mc}/{args.loader}/{installer}/server/jar",
        root / "fabric-server.jar",
    )
    mods = root / "mods"
    mods.mkdir(exist_ok=True)
    download(
        f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{args.api}/fabric-api-{args.api}.jar",
        mods / f"fabric-api-{args.api}.jar",
    )
    jar = Path(args.jar)
    if args.relax_loader:
        relax_fabric_loader(jar, mods / jar.name)
    else:
        shutil.copy(jar, mods / jar.name)
    return ["-jar", "fabric-server.jar", "nogui"]


def prepare_quilt(args, root: Path) -> list[str]:
    write_server_files(root)
    installer = fetch_json("https://meta.quiltmc.org/v3/versions/installer")[0]["version"]
    installer_jar = download(
        f"https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/{installer}/quilt-installer-{installer}.jar",
        root.parent / "quilt-installer.jar",
    )
    command = ["java", "-jar", str(installer_jar), "install", "server", args.mc]
    if args.loader:
        command.append(args.loader)
    command += ["--download-server", f"--install-dir={root}"]
    subprocess.run(command, check=True)
    mods = root / "mods"
    mods.mkdir(exist_ok=True)
    download(
        f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{args.api}/fabric-api-{args.api}.jar",
        mods / f"fabric-api-{args.api}.jar",
    )
    jar = Path(args.jar)
    if args.drop_quilt_json:
        drop_quilt_metadata(jar, mods / jar.name)
    else:
        shutil.copy(jar, mods / jar.name)
    return ["-jar", "quilt-server-launch.jar", "nogui"]


def prepare_paper(args, root: Path) -> list[str]:
    write_server_files(root)
    build = fetch_json(f"https://fill.papermc.io/v3/projects/paper/versions/{args.mc}/builds/latest")
    download(build["downloads"]["server:default"]["url"], root / "paper.jar")
    plugins = root / "plugins"
    plugins.mkdir(exist_ok=True)
    shutil.copy(args.jar, plugins / Path(args.jar).name)
    return ["-Dpaper.disableOldApiSupport=true", "-jar", "paper.jar", "--nogui"]


def run_server(root: Path, launch: list[str], log_path: Path) -> tuple[bool, int | None]:
    """Runs the server until it reports Done (then stops it) or the timeout. Returns (booted, exit code)."""
    command = [
        "java",
        "-Xmx3G",
        "-Dmixin.debug.verify=true",
        "-Dmixin.debug.countInjections=true",
        *launch,
    ]
    print("$ " + " ".join(command), flush=True)
    process = subprocess.Popen(
        command,
        cwd=root,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    booted = threading.Event()

    def pump() -> None:
        with log_path.open("w") as log:
            assert process.stdout is not None
            for line in process.stdout:
                sys.stdout.write(line)
                log.write(line)
                if not booted.is_set() and "Done (" in line:
                    booted.set()
                    try:
                        assert process.stdin is not None
                        process.stdin.write("stop\n")
                        process.stdin.flush()
                    except OSError:
                        pass

    reader = threading.Thread(target=pump, daemon=True)
    reader.start()
    try:
        code = process.wait(timeout=BOOT_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        process.kill()
        code = None
    reader.join(timeout=30)
    return booted.is_set(), code


def analyse(kind: str, log: str, booted: bool, code: int | None) -> list[str]:
    problems = []
    if not booted:
        problems.append("server never reported Done" if code is not None else "server timed out before Done")
    lines = log.splitlines()
    for marker in FATAL_MARKERS:
        hit = next((line for line in lines if marker in line), None)
        if hit:
            problems.append(f"`{marker}`: {hit.strip()[:300]}")
    own = [line for line in lines if OWN_ERROR.search(line)]
    if own:
        problems.append(f"DreamDisplays error: {own[0].strip()[:300]}")
    if kind in ("fabric", "quilt") and not re.search(r"(^|[-|])\s*dreamdisplays\b", log, re.MULTILINE | re.IGNORECASE):
        problems.append("dreamdisplays is not in the loaded mod list")
    if kind == "paper" and "Enabling DreamDisplays" not in log:
        problems.append("plugin was never enabled")
    return problems


def resolve_jar(pattern: str) -> str:
    """Accepts a path or a glob; for a glob, picks the plain jar (no -sources / -dev classifier)."""
    if Path(pattern).is_file():
        return pattern
    matches = sorted(Path().glob(pattern), key=lambda p: len(p.name))
    matches = [p for p in matches if not re.search(r"-(sources|javadoc|dev-shadow)\.jar$", p.name)]
    if not matches:
        raise SystemExit(f"No jar matches {pattern}: {sorted(os.listdir(Path(pattern).parent or '.'))}")
    return str(matches[0])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("fabric", "quilt", "paper"))
    parser.add_argument("--mc", default="")
    parser.add_argument("--jar", default="")
    parser.add_argument("--loader", default="")
    parser.add_argument("--api", default="")
    parser.add_argument("--relax-loader", action="store_true", help="open fabricloader to '*' to probe older loaders")
    parser.add_argument("--drop-quilt-json", action="store_true", help="remove quilt.mod.json before loading on Quilt")
    parser.add_argument("--label", default="")
    parser.add_argument("--work", default="smoke-server")
    parser.add_argument("--check-log", default="", help="only check an existing log (e.g. a client run)")
    args = parser.parse_args()

    if args.check_log:
        log_file = Path(args.check_log)
        log = log_file.read_text(errors="replace") if log_file.exists() else ""
        problems = analyse(args.kind, log, booted=bool(log), code=0) if log else [f"no log at {log_file}"]
        return report(args.label or f"{args.kind} log", problems)

    args.jar = resolve_jar(args.jar)
    print(f"Testing {args.jar}", flush=True)

    root = Path(args.work).resolve() / "server"
    shutil.rmtree(root.parent, ignore_errors=True)
    root.mkdir(parents=True)
    prepare = {"fabric": prepare_fabric, "quilt": prepare_quilt, "paper": prepare_paper}[args.kind]
    try:
        launch = prepare(args, root)
    except Exception as error:  # noqa: BLE001 - a setup failure is a result too
        problems = [f"setup failed: {error}"]
        booted, log = False, ""
    else:
        log_path = root.parent / "server.log"
        booted, code = run_server(root, launch, log_path)
        log = log_path.read_text(errors="replace") if log_path.exists() else ""
        problems = analyse(args.kind, log, booted, code)

    return report(args.label or f"{args.kind} {args.mc} {args.loader}".strip(), problems)


def report(label: str, problems: list[str]) -> int:
    verdict = "✅ starts cleanly" if not problems else "❌ " + "; ".join(problems)
    print(f"\n{label}: {verdict}", flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as out:
            out.write(f"| {label} | {verdict.replace('|', '/')} |\n")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
