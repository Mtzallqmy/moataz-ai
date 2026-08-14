#!/usr/bin/env python3
"""Fast, dependency-free repository sanity checks for Aegis.

This does not replace Gradle/Android/native tests. It catches merge residue,
unsafe release bypasses, malformed XML/TOML, risky committed key material,
and common Gradle block corruption before CI spends time building the project.
"""
from __future__ import annotations

import hashlib
import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {".kt", ".kts", ".java", ".rs", ".cpp", ".c", ".h", ".hpp", ".xml", ".toml", ".yml", ".yaml", ".md", ".properties", ".pro"}
SKIP_PARTS = {".git", ".gradle", "build", ".idea"}
RISKY_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
MERGE_MARKER = re.compile(r"^(<<<<<<<|=======|>>>>>>>)", re.MULTILINE)
LINT_BYPASS = re.compile(r"-Plint=false|android\.lintVital\.enabled\s*=\s*false")
CONTINUE_ON_ERROR = re.compile(r"continue-on-error\s*:\s*true", re.IGNORECASE)
UNSAFE_SSH_ARGUMENT = re.compile(r"add\(\s*[\"']StrictHostKeyChecking=no[\"']\s*\)", re.IGNORECASE)
EXPECTED_GRADLE_DISTRIBUTION_SHA256 = "544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d"
# This checkout currently carries a recognized older official wrapper bootstrap jar.
# CI wrapper-validation is authoritative; align it with 8.7 by regenerating the wrapper
# on a networked machine when practical.
RECOGNIZED_WRAPPER_SHA256 = {
    "d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd",
    "cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8",
}

errors: list[str] = []
warnings: list[str] = []


def files():
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(part in SKIP_PARTS for part in path.parts):
            continue
        yield path


def read_text(path: Path) -> str | None:
    if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in {"gradlew", "gradle.properties"}:
        return None
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return None


def block_ranges(text: str, keyword: str) -> list[tuple[int, int]]:
    """Best-effort balanced-brace ranges for Gradle top-level named blocks."""
    out = []
    for match in re.finditer(rf"(?m)^\s*{re.escape(keyword)}\s*\{{", text):
        start_brace = text.find("{", match.start())
        depth = 0
        quote: str | None = None
        escape = False
        i = start_brace
        while i < len(text):
            ch = text[i]
            if quote:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == quote:
                    quote = None
            else:
                if ch in {'"', "'"}:
                    quote = ch
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        out.append((match.start(), i + 1))
                        break
            i += 1
    return out


for path in files():
    rel = path.relative_to(ROOT)
    if path.suffix.lower() in RISKY_SUFFIXES or path.name == ".env":
        errors.append(f"risky key/secret material is tracked: {rel}")
    text = read_text(path)
    if text is None:
        continue
    if MERGE_MARKER.search(text):
        errors.append(f"merge marker: {rel}")
    # Historical documentation may mention a removed bypass; executable/config source may not use it.
    if path.suffix.lower() != ".md" and LINT_BYPASS.search(text):
        errors.append(f"lint bypass: {rel}")
    if path.suffix.lower() in {".yml", ".yaml"} and CONTINUE_ON_ERROR.search(text):
        errors.append(f"CI continue-on-error: {rel}")
    if path.suffix.lower() in {".kt", ".kts", ".sh"} and UNSAFE_SSH_ARGUMENT.search(text):
        errors.append(f"unsafe SSH host-key bypass argument: {rel}")

# XML/TOML structural validation.
for path in files():
    rel = path.relative_to(ROOT)
    try:
        if path.suffix.lower() == ".xml":
            ET.parse(path)
        elif path.suffix.lower() == ".toml":
            with path.open("rb") as fh:
                tomllib.load(fh)
    except Exception as exc:
        errors.append(f"parse failure {rel}: {exc}")

# Catch the merge-corruption pattern that previously put android{} in dependencies{}.
for path in ROOT.rglob("build.gradle.kts"):
    if any(part in SKIP_PARTS for part in path.parts):
        continue
    text = path.read_text(encoding="utf-8")
    for start, end in block_ranges(text, "dependencies"):
        body = text[start:end]
        if re.search(r"(?m)^\s*android\s*\{", body):
            errors.append(f"android block nested inside dependencies: {path.relative_to(ROOT)}")

# Wrapper integrity / reproducibility checks.
props = ROOT / "gradle/wrapper/gradle-wrapper.properties"
if not props.exists():
    errors.append("missing gradle-wrapper.properties")
else:
    text = props.read_text(encoding="utf-8")
    if "gradle-8.7-bin.zip" not in text:
        warnings.append("Gradle distribution is not 8.7; confirm intentional version change")
    if f"distributionSha256Sum={EXPECTED_GRADLE_DISTRIBUTION_SHA256}" not in text:
        errors.append("Gradle 8.7 distributionSha256Sum is missing or unexpected")

wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
if not wrapper_jar.exists():
    errors.append("missing gradle-wrapper.jar")
else:
    digest = hashlib.sha256(wrapper_jar.read_bytes()).hexdigest()
    if digest not in RECOGNIZED_WRAPPER_SHA256:
        errors.append(f"unrecognized Gradle wrapper jar SHA-256: {digest}")
    elif digest != "cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8":
        warnings.append("Gradle wrapper bootstrap jar is recognized but older than the 8.7 distribution; regenerate wrapper with Gradle 8.7 when network is available")

# Required hardening files.
for required in [
    "app/proguard-rules.pro",
    ".github/workflows/build.yml",
    ".github/workflows/secret-scan.yml",
    "core/database/schemas/.gitkeep",
]:
    if not (ROOT / required).exists():
        errors.append(f"required hardening file missing: {required}")

# Native boundary should be deliberate and pinned.
cmake = ROOT / "native/local-llm/src/main/cpp/CMakeLists.txt"
if cmake.exists():
    ctext = cmake.read_text(encoding="utf-8")
    tag = re.search(r"GIT_TAG\s+([0-9a-f]{40})", ctext)
    if not tag:
        errors.append("llama.cpp FetchContent GIT_TAG is not pinned to a 40-char commit")

print(f"Aegis static verification: {ROOT}")
if warnings:
    print("WARNINGS:")
    for item in warnings:
        print(f"  - {item}")
if errors:
    print("ERRORS:")
    for item in errors:
        print(f"  - {item}")
    sys.exit(1)
print("PASS: no blocking static repository issues found")
