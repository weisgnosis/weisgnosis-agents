"""Inspect freshly packaged distribution files and stage an isolated Maven resolver."""

import hashlib
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile


MODULES = ("core", "harness", "mcp")
VERSION = "0.1.0-SNAPSHOT"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TEST_ONLY = frozenset((
    "zio-test_3", "zio-test-sbt_3", "mcp-core", "mcp-json-jackson2",
    "tomcat-embed-core", "slf4j-jdk14",
))


def require(condition, message):
    if not condition:
        sys.exit("Artifact verification failed: " + message)


def inspect(root, module, destination):
    folder = root / "modules" / module
    artifact = "weisgnosis-agents-" + module + "_3"
    output = folder / "target" / "scala-3.9.0"
    binary = output / (artifact + "-" + VERSION + ".jar")
    sources = output / (artifact + "-" + VERSION + "-sources.jar")
    pom = output / (artifact + "-" + VERSION + ".pom")
    for path in (binary, sources, pom):
        require(path.is_file(), "missing packaged file " + str(path))
    license_bytes = (root / "LICENSE").read_bytes()
    for path in (binary, sources):
        with ZipFile(path) as archive:
            require(archive.namelist().count("META-INF/LICENSE") == 1, str(path) + " license count")
            require(archive.read("META-INF/LICENSE") == license_bytes, str(path) + " license bytes")
    with ZipFile(binary) as archive:
        names = archive.namelist()
        require(any(name.endswith(".class") for name in names), module + " missing classes")
        require(any(name.endswith(".tasty") for name in names), module + " missing TASTy")
        require(not any(name.endswith("Spec.class") for name in names), module + " includes tests")
    with ZipFile(sources) as archive:
        source_root = folder / "src" / "main" / "scala"
        current = tuple(source_root.rglob("*.scala"))
        require(bool(current), module + " has no production sources")
        for source in current:
            entry = source.relative_to(source_root).as_posix()
            require(entry in archive.namelist(), module + " missing source " + entry)
            require(archive.read(entry).replace(b"\r\n", b"\n") == source.read_bytes().replace(b"\r\n", b"\n"),
                    module + " source differs from current tree: " + entry)
    tree = ET.parse(pom).getroot()
    require(tree.findtext("m:groupId", namespaces=NS) == "com.weisgnosis", module + " group")
    require(tree.findtext("m:artifactId", namespaces=NS) == artifact, module + " artifact")
    require(tree.findtext("m:version", namespaces=NS) == VERSION, module + " version")
    dependencies = tree.findall("m:dependencies/m:dependency", NS)
    if module != "core":
        require(any(
            dependency.findtext("m:groupId", namespaces=NS) == "com.weisgnosis"
            and dependency.findtext("m:artifactId", namespaces=NS) == "weisgnosis-agents-core_3"
            and dependency.findtext("m:version", namespaces=NS) == VERSION
            and dependency.findtext("m:scope", default="compile", namespaces=NS) == "compile"
            for dependency in dependencies
        ), module + " missing matching compile core dependency")
    for dependency in dependencies:
        name = dependency.findtext("m:artifactId", namespaces=NS)
        scope = dependency.findtext("m:scope", default="compile", namespaces=NS)
        if name in TEST_ONLY:
            require(scope == "test", module + " leaked test dependency " + name)
        if name == "snakeyaml-engine":
            require(module == "harness", module + " leaked YAML dependency")
    staged = destination / "repository" / "com" / "weisgnosis" / artifact / VERSION
    staged.mkdir(parents=True, exist_ok=True)
    for path in (binary, sources, pom):
        shutil.copyfile(path, staged / path.name)
    print(module + ": exact license/source content, classes/TASTy and POM boundaries passed")
    return hashlib.sha256(binary.read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[2]
    destination = root / "target" / "artifact-verification"
    manifest = destination / "manifest.json"
    destination.mkdir(parents=True, exist_ok=True)
    # An interrupted or failed preparation must never leave a previously valid manifest.
    manifest.unlink(missing_ok=True)
    hashes = {module: inspect(root, module, destination) for module in MODULES}
    manifest.write_text(json.dumps({"version": VERSION, "sha256": hashes}), encoding="utf-8")
    print("Fresh module hashes recorded for verification of the actually loaded consumer JARs")


if __name__ == "__main__":
    main()
