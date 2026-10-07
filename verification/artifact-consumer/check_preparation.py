"""Exercise source-archive admission with reversible, sequential packaged fixtures."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
import warnings
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version")
    arguments = parser.parse_args()
    if arguments.version is not None and (len(arguments.version) > 64 or
            re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-RC[1-9][0-9]*", arguments.version, re.ASCII) is None):
        sys.exit("Explicit fixture version must be a canonical ASCII RC version")
    version = arguments.version if arguments.version is not None else "0.1.0-SNAPSHOT"
    root = Path(__file__).resolve().parents[2]
    verifier = root / "verification" / "artifact-consumer" / "prepare.py"
    command = [sys.executable, str(verifier)]
    if arguments.version is not None:
        command += ["--version", arguments.version]
    archive = root / "modules" / "harness" / "target" / "scala-3.9.0" / (
        "weisgnosis-agents-harness_3-" + version + "-sources.jar")
    manifest = root / "target" / "artifact-verification" / "manifest.json"
    original = archive.read_bytes()
    failures = []

    def verify():
        return subprocess.run(command, cwd=root, capture_output=True, text=True,
                              encoding="utf-8", check=False)

    try:
        baseline = verify()
        if baseline.returncode != 0:
            sys.exit("Valid baseline rejected: " + baseline.stderr)
        invalid = subprocess.run([sys.executable, str(verifier), "--version", "../invalid"],
                                 cwd=root, capture_output=True, text=True, encoding="utf-8", check=False)
        invalidated = not manifest.exists()
        print("invalid selector: verifier exit " + str(invalid.returncode) +
              ", manifest invalidated " + str(invalidated))
        if invalid.returncode == 0 or not invalidated:
            failures.append("invalid selector must reject and invalidate the manifest")
        baseline = verify()
        if baseline.returncode != 0:
            sys.exit("Valid baseline rejected after selector check: " + baseline.stderr)
        with ZipFile(archive) as source:
            existing = next((name for name in source.namelist() if name.endswith(".scala")), None)
            if existing is None:
                sys.exit("Fixture requires a packaged Scala source")
            existing_bytes = source.read(existing)
        cases = (
            ("unexpected Scala source", "com/weisgnosis/agents/Obsolete.scala", b"package com.weisgnosis.agents\n"),
            ("duplicate Scala source", existing, existing_bytes),
        )
        for label, entry, content in cases:
            archive.write_bytes(original)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with ZipFile(archive, "a") as modified:
                    modified.writestr(entry, content)
            result = verify()
            rejected = result.returncode != 0
            invalidated = not manifest.exists()
            print(label + ": verifier exit " + str(result.returncode) +
                  ", manifest invalidated " + str(invalidated))
            if not rejected or not invalidated:
                failures.append(label + " must reject and invalidate the manifest")
    finally:
        archive.write_bytes(original)
    restored = verify()
    if restored.returncode != 0:
        failures.append("restored valid artifact must pass: " + restored.stderr)
    if failures:
        sys.exit("\n".join(failures))
    print("Selector and source-archive regressions passed; original bytes restored and revalidated")


if __name__ == "__main__":
    main()
