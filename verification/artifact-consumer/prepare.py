"""Inspect freshly packaged distribution files and stage an isolated Maven resolver."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile


MODULES = ("core", "harness", "mcp", "storage-postgresql", "otel")
DEFAULT_VERSION = "0.1.0-SNAPSHOT"
RC_VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-RC[1-9][0-9]*", re.ASCII)
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TEST_ONLY = frozenset((
    "zio-test_3", "zio-test-sbt_3", "mcp-core", "mcp-json-jackson2",
    "tomcat-embed-core", "slf4j-jdk14", "postgresql",
    "opentelemetry-sdk", "opentelemetry-sdk-trace", "opentelemetry-sdk-common",
    "opentelemetry-sdk-testing",
    "opentelemetry-exporter-otlp", "opentelemetry-exporter-otlp-common",
    "opentelemetry-exporter-common", "opentelemetry-exporter-sender-okhttp",
))


def require(condition, message):
    if not condition:
        sys.exit("Artifact verification failed: " + message)


def inspect(root, module, destination, version):
    folder = root / "modules" / module
    artifact = "weisgnosis-agents-" + module + "_3"
    output = folder / "target" / "scala-3.9.0"
    binary = output / (artifact + "-" + version + ".jar")
    sources = output / (artifact + "-" + version + "-sources.jar")
    docs = output / (artifact + "-" + version + "-javadoc.jar")
    pom = output / (artifact + "-" + version + ".pom")
    for path in (binary, sources, docs, pom):
        require(path.is_file(), "missing packaged file " + str(path))
    license_bytes = (root / "LICENSE").read_bytes()
    for path in (binary, sources, docs):
        with ZipFile(path) as archive:
            require(archive.namelist().count("META-INF/LICENSE") == 1, str(path) + " license count")
            require(archive.read("META-INF/LICENSE") == license_bytes, str(path) + " license bytes")
    with ZipFile(binary) as archive:
        names = archive.namelist()
        require(any(name.endswith(".class") for name in names), module + " missing classes")
        require(any(name.endswith(".tasty") for name in names), module + " missing TASTy")
        require(not any(name.endswith("Spec.class") for name in names), module + " includes tests")
    with ZipFile(docs) as archive:
        require("index.html" in archive.namelist(), module + " missing Scaladoc index")
    with ZipFile(sources) as archive:
        source_root = folder / "src" / "main" / "scala"
        current = tuple(source_root.rglob("*.scala"))
        require(bool(current), module + " has no production sources")
        expected_entries = tuple(source.relative_to(source_root).as_posix() for source in current)
        packaged_entries = tuple(name for name in archive.namelist() if name.endswith(".scala"))
        require(sorted(packaged_entries) == sorted(expected_entries),
                module + " source archive entry names/counts differ from current Scala sources")
        for source in current:
            entry = source.relative_to(source_root).as_posix()
            require(entry in archive.namelist(), module + " missing source " + entry)
            require(archive.read(entry).replace(b"\r\n", b"\n") == source.read_bytes().replace(b"\r\n", b"\n"),
                    module + " source differs from current tree: " + entry)
    tree = ET.parse(pom).getroot()
    require(tree.findtext("m:groupId", namespaces=NS) == "com.weisgnosis", module + " group")
    require(tree.findtext("m:artifactId", namespaces=NS) == artifact, module + " artifact")
    require(tree.findtext("m:version", namespaces=NS) == version, module + " version")
    require(bool(tree.findtext("m:description", default="", namespaces=NS).strip()),
            module + " missing description")
    dependencies = tree.findall("m:dependencies/m:dependency", NS)
    if module != "core":
        parent = "harness" if module == "storage-postgresql" else "core"
        require(any(
            dependency.findtext("m:groupId", namespaces=NS) == "com.weisgnosis"
            and dependency.findtext("m:artifactId", namespaces=NS) == "weisgnosis-agents-" + parent + "_3"
            and dependency.findtext("m:version", namespaces=NS) == version
            and dependency.findtext("m:scope", default="compile", namespaces=NS) == "compile"
            for dependency in dependencies
        ), module + " missing matching compile parent dependency")
    for dependency in dependencies:
        name = dependency.findtext("m:artifactId", namespaces=NS)
        scope = dependency.findtext("m:scope", default="compile", namespaces=NS)
        if name in TEST_ONLY:
            require(scope == "test", module + " leaked test dependency " + name)
        if name == "snakeyaml-engine":
            require(module == "harness", module + " leaked YAML dependency")
    staged = destination / "repository" / "com" / "weisgnosis" / artifact / version
    staged.mkdir(parents=True, exist_ok=True)
    for path in (binary, sources, docs, pom):
        shutil.copyfile(path, staged / path.name)
    print(module + ": exact license/source content, Scaladoc index, classes/TASTy and POM boundaries passed")
    return hashlib.sha256(binary.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="Explicit candidate version, for example 0.1.0-RC1")
    parser.add_argument("--check-only", action="store_true", help="Validate version without reading or staging artifacts")
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    destination = root / "target" / "artifact-verification"
    manifest = destination / "manifest.json"
    if not arguments.check_only:
        destination.mkdir(parents=True, exist_ok=True)
        # Invalidate prior authority before selector validation or artifact inspection.
        manifest.unlink(missing_ok=True)
    if arguments.version is not None:
        require(len(arguments.version) <= 64 and RC_VERSION.fullmatch(arguments.version) is not None,
                "explicit version must be canonical ASCII major.minor.patch-RCpositive without leading zeros")
    version = arguments.version if arguments.version is not None else DEFAULT_VERSION
    if arguments.check_only:
        print("Checked artifact version: " + version)
        return
    hashes = {module: inspect(root, module, destination, version) for module in MODULES}
    manifest.write_text(json.dumps({"version": version, "sha256": hashes}), encoding="utf-8")
    print("Fresh module hashes recorded for verification of the actually loaded consumer JARs")


if __name__ == "__main__":
    main()
