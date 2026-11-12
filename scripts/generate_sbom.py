#!/usr/bin/env python3
"""Generate declared-dependency SPDX and CycloneDX SBOMs for AIRI.

The Android graph is intentionally labelled declared/incomplete unless a resolved
Gradle graph is supplied. This avoids presenting a direct catalog as a complete
transitive SBOM when an Android SDK is unavailable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
NS = {"v": "https://schema.gradle.org/dependency-verification"}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def git(*args: str) -> str:
    try:
        return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()
    except Exception:
        return "UNKNOWN"


def gradle_components() -> list[dict]:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
    versions = catalog.get("versions", {})
    result = []
    for alias, value in catalog.get("libraries", {}).items():
        group = value.get("group")
        name = value.get("name")
        version = value.get("version")
        if isinstance(version, dict):
            version = versions.get(version.get("ref"))
        if not group or not name:
            continue
        result.append({
            "purl": f"pkg:maven/{quote(group, safe='')}/{quote(name, safe='')}@{version or 'BOM-managed'}",
            "group": group,
            "name": name,
            "version": version or "BOM-managed",
            "scope": "runtime",
            "license": "NOASSERTION",
            "source": f"https://repo1.maven.org/maven2/{group.replace('.', '/')}/{name}/",
        })
    return result


def npm_components() -> list[dict]:
    path = ROOT / "functions/package-lock.json"
    if not path.exists():
        return []
    lock = json.loads(path.read_text())
    result = []
    for key, value in lock.get("packages", {}).items():
        if key == "" or not key.startswith("node_modules/"):
            continue
        name = key.removeprefix("node_modules/")
        version = value.get("version")
        if not version:
            continue
        result.append({
            "purl": f"pkg:npm/{quote(name, safe='@/') }@{version}",
            "group": "npm",
            "name": name,
            "version": version,
            "scope": "runtime",
            "license": value.get("license", "NOASSERTION"),
            "source": value.get("resolved", f"https://registry.npmjs.org/{name}"),
            "integrity": value.get("integrity"),
        })
    return result


def verification_hashes() -> dict[str, str]:
    path = ROOT / "gradle/verification-metadata.xml"
    if not path.exists():
        return {}
    root = ET.parse(path).getroot()
    result = {}
    for component in root.findall("v:components/v:component", NS):
        coordinate = (component.get("group"), component.get("name"), component.get("version"))
        for artifact in component.findall("v:artifact", NS):
            digest = artifact.find("v:sha256", NS)
            if digest is not None and digest.get("value"):
                result[(coordinate, artifact.get("name"))] = digest.get("value")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=ROOT / "docs/supply-chain")
    args = parser.parse_args()
    out = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    components = gradle_components() + npm_components()
    metadata_hashes = verification_hashes()
    for c in components:
        if c["group"] != "npm":
            artifact = f"{c['name']}-{c['version']}.aar"
            c["verification_sha256"] = metadata_hashes.get(((c["group"], c["name"], c["version"]), artifact))
    timestamp = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    commit = git("rev-parse", "HEAD")
    bom_ref = f"AIRI-{commit}"
    incomplete = True
    notes = [
        "Gradle entries are version-catalog declarations; a complete Android transitive graph requires a successful Gradle resolution on a machine with the Android SDK.",
        "Functions entries come from functions/package-lock.json and include npm integrity fields where npm supplied them.",
        "NOASSERTION is intentional where a license was not obtained from a trusted package metadata source.",
    ]

    spdx_packages = []
    relationships = []
    for i, c in enumerate(components, 1):
        ident = f"SPDXRef-Package-{i}"
        checksums = []
        if c.get("verification_sha256"):
            checksums.append({"algorithm": "SHA256", "checksumValue": c["verification_sha256"]})
        spdx_packages.append({
            "SPDXID": ident,
            "name": c["name"],
            "versionInfo": c["version"],
            "downloadLocation": c["source"],
            "licenseConcluded": c["license"],
            "licenseDeclared": c["license"],
            "copyrightText": "NOASSERTION",
            "externalRefs": [{"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl", "referenceLocator": c["purl"]}],
            **({"checksums": checksums} if checksums else {}),
        })
        relationships.append({"spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES", "relatedSpdxElement": ident})

    spdx = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "AIRI-declared-dependencies",
        "documentNamespace": f"https://github.com/AbdulLatef2380/AIRI-Project/sbom/{commit}",
        "creationInfo": {"created": timestamp, "creators": ["Tool: AIRI scripts/generate_sbom.py"]},
        "comment": "INCOMPLETE_DECLARED_GRAPH: " + " ".join(notes),
        "packages": spdx_packages,
        "relationships": relationships,
    }
    (out / "SBOM.spdx.json").write_text(json.dumps(spdx, indent=2, ensure_ascii=False) + "\n")

    cdx_components = []
    for c in components:
        item = {"type": "library", "bom-ref": c["purl"], "group": c["group"], "name": c["name"], "version": c["version"], "purl": c["purl"], "licenses": [{"license": {"id": c["license"]}}]}
        if c.get("integrity"):
            item["properties"] = [{"name": "npm.integrity", "value": c["integrity"]}]
        if c.get("verification_sha256"):
            item["hashes"] = [{"alg": "SHA-256", "content": c["verification_sha256"]}]
        cdx_components.append(item)
    cdx = {
        "bomFormat": "CycloneDX", "specVersion": "1.5", "serialNumber": f"urn:uuid:{commit[:8]}-airi-sbom", "version": 1,
        "metadata": {"timestamp": timestamp, "tools": [{"vendor": "AIRI", "name": "generate_sbom.py", "version": "1"}], "component": {"type": "application", "name": "AIRI", "version": commit}},
        "properties": [{"name": "airi.graph.status", "value": "incomplete-declared-direct-plus-node-lock"}, {"name": "airi.commit", "value": commit}],
        "components": cdx_components,
    }
    (out / "SBOM.cyclonedx.json").write_text(json.dumps(cdx, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"commit": commit, "components": len(components), "gradle_direct": len(gradle_components()), "npm_lock": len(npm_components()), "output": str(out)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
