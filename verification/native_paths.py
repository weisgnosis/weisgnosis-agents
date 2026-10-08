"""Run native alias screening against a disposable, explicitly owned directory fixture."""

import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Iterator


SELECTOR = "core / testOnly com.weisgnosis.agents.core.tool.PathSafetySpec"
ALIASES = (("ordinary-alias", "ordinary"), ("sensitive-alias", ".ssh"))


def require(condition: bool, message: str) -> None:
    if not condition:
        sys.exit("Native path verification failed: " + message)


def remove_alias(root: Path, name: str) -> None:
    alias = root / name
    if os.path.lexists(alias):
        attributes = alias.lstat()
        if os.name == "nt":
            require(bool(attributes.st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT),
                    "refusing to remove a non-junction alias: " + str(alias))
            os.rmdir(alias)
        else:
            require(stat.S_ISLNK(attributes.st_mode), "refusing to remove a non-symlink alias")
            alias.unlink()


def remove_target(root: Path, name: str) -> None:
    target = root / name
    if target.exists():
        require(target.resolve(strict=True) == target, "target directory changed identity")
        marker = target / "marker.txt"
        if marker.exists():
            require(stat.S_ISREG(marker.lstat().st_mode), "marker changed file type")
            marker.unlink()
        target.rmdir()


@contextmanager
def fixture() -> Iterator[Path]:
    parent = Path(tempfile.gettempdir()).resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix="weisgnosis-native-paths-", dir=parent)).resolve(strict=True)
    require(root.parent == parent, "temporary root escaped its selected parent")
    try:
        tuple(map(lambda name: (root / name).mkdir(), ("ordinary", ".ssh")))
        tuple(map(lambda name: (root / name / "marker.txt").write_bytes(b"owned fixture\n"),
                  ("ordinary", ".ssh")))

        def link(pair: tuple[str, str]) -> None:
            alias_name, target_name = pair
            target = (root / target_name).resolve(strict=True)
            require(target.is_relative_to(root), "alias target escaped its owned root")
            alias = root / alias_name
            if os.name == "nt":
                import _winapi
                _winapi.CreateJunction(str(target), str(alias))
            else:
                alias.symlink_to(target, target_is_directory=True)
            require(alias.resolve(strict=True) == target, "native alias did not resolve to its target")

        tuple(map(link, ALIASES))
        yield root
    finally:
        tuple(map(lambda pair: remove_alias(root, pair[0]), ALIASES))
        tuple(map(lambda name: remove_target(root, name), ("ordinary", ".ssh")))
        root.rmdir()


def run(command: tuple[str, ...], project: Path, environment: dict[str, str], timeout: int) -> int:
    # Join the direct launcher before removing its borrowed filesystem fixture, including on Ctrl-C.
    with subprocess.Popen(command, cwd=project, env=environment) as process:
        try:
            return process.wait(timeout=timeout)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"), help="JDK 21 installation")
    parser.add_argument("--sbt-launch", type=Path, required=True, help="sbt launcher JAR, run directly with JDK 21")
    parser.add_argument("--full", action="store_true", help="run verify with the live fixture instead of only PathSafetySpec")
    parser.add_argument("--timeout", type=int, default=1200, help="bounded launcher wait in seconds (1..3600)")
    args = parser.parse_args()
    require(bool(args.java_home), "set JAVA_HOME or supply --java-home for JDK 21")
    require(1 <= args.timeout <= 3600, "timeout must be between 1 and 3600 seconds")
    java_home = Path(args.java_home).resolve(strict=True)
    release = (java_home / "release").read_text(encoding="utf-8")
    require(re.search(r'^JAVA_VERSION="21(?:[.+-]|")', release, re.MULTILINE) is not None,
            "the selected JAVA_HOME is not JDK 21")
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    require(java.is_file(), "selected JDK has no java executable")
    task = "verify" if args.full else SELECTOR
    jar = args.sbt_launch.resolve(strict=True)
    require(jar.is_file(), "sbt launcher JAR is not a regular file")
    project = Path(__file__).resolve(strict=True).parent.parent
    # Each non-comment .jvmopts line is one JVM argument; no shell interpolation.
    jvm_options = tuple(line.strip() for line in (project / ".jvmopts").read_text(
        encoding="utf-8").splitlines() if line.strip() and not line.lstrip().startswith("#"))
    command = (str(java), *jvm_options, "-jar", str(jar),
               "set core / Test / fork := false", task)
    environment = dict(os.environ, JAVA_HOME=str(java_home),
                       PATH=str(java_home / "bin") + os.pathsep + os.environ.get("PATH", ""))
    with fixture() as root:
        environment["WEISGNOSIS_PATH_FIXTURE"] = str(root)
        print("Native path fixture: " + str(root), flush=True)
        return run(command, project, environment, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
