#!/usr/bin/env python3
"""Package the verified Spring Boot JAR using only Python's standard library."""

import argparse
import hashlib
import re
import stat
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[1]


def project_version():
    version = ET.parse(ROOT / "pom.xml").getroot().findtext(
        "{http://maven.apache.org/POM/4.0.0}version"
    )
    if not version or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Release version in pom.xml must have the form X.Y.Z")
    return version


def package(version):
    files = {
        "bunker-server.jar": ROOT / "target/bunker-server.jar",
        "start.cmd": ROOT / "distribution/start.cmd",
        "start.sh": ROOT / "distribution/start.sh",
        "README.md": ROOT / "distribution/README.md",
        "schemas/bunker.schema.json": ROOT / "schemas/bunker.schema.json",
    }
    for source in files.values():
        if not source.is_file():
            raise FileNotFoundError(f"Missing release file: {source}")
    with ZipFile(files["bunker-server.jar"]) as jar:
        if "BOOT-INF/classes/ru/bunker/BunkerApplication.class" not in jar.namelist():
            raise ValueError("Expected an executable Spring Boot JAR; run mvn clean verify first")

    destination = ROOT / "dist"
    destination.mkdir(exist_ok=True)
    name = f"bunker-selfhost-{version}"
    archive = destination / f"{name}.zip"
    with ZipFile(archive, "w", compression=ZIP_DEFLATED, compresslevel=9) as output:
        for relative, source in files.items():
            # Fixed timestamps and permissions keep the archive independent of the host OS.
            entry = ZipInfo(f"{name}/{relative}", date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            mode = 0o755 if relative == "start.sh" else 0o644
            entry.external_attr = (stat.S_IFREG | mode) << 16
            entry.compress_type = ZIP_DEFLATED
            data = source.read_bytes()
            if relative != "bunker-server.jar":
                data = data.replace(b"\r\n", b"\n")
                if relative == "start.cmd":
                    data = data.replace(b"\n", b"\r\n")
            output.writestr(entry, data)
    with ZipFile(archive) as output:
        if output.testzip() is not None:
            raise ValueError("Release ZIP failed its CRC check")
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(".zip.sha256").write_text(
        f"{checksum}  {archive.name}\n", encoding="ascii", newline="\n"
    )
    print(archive)
    print(f"SHA-256: {checksum}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-only", action="store_true")
    args = parser.parse_args()
    version = project_version()
    if args.version_only:
        print(version)
    else:
        package(version)
