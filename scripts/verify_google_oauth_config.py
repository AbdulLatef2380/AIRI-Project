#!/usr/bin/env python3
"""Validate the Firebase/Google OAuth inputs required by AIRI Android sign-in."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

SHA1_RE = re.compile(r"^[0-9a-fA-F]{40}$")
WEB_CLIENT_RE = re.compile(r"^[0-9]+-[a-zA-Z0-9-]+\.apps\.googleusercontent\.com$")


class ConfigError(ValueError):
    pass


def normalize_sha1(value: str) -> str:
    return re.sub(r"[^0-9a-fA-F]", "", value).lower()


def inspect_config(path: Path, application_id: str) -> dict[str, Any]:
    if not path.is_file():
        raise ConfigError(f"Firebase config does not exist: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ConfigError(f"Firebase config is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ConfigError("google-services.json root must be an object")

    project = data.get("project_info")
    if not isinstance(project, dict):
        raise ConfigError("google-services.json has no project_info object")
    project_number = str(project.get("project_number", "")).strip()
    if not project_number.isdigit():
        raise ConfigError("Firebase project_number is missing or invalid")

    matching_clients = []
    for client in data.get("client", []):
        if not isinstance(client, dict):
            continue
        client_info = client.get("client_info", {})
        android_info = client_info.get("android_client_info", {}) if isinstance(client_info, dict) else {}
        if isinstance(android_info, dict) and android_info.get("package_name") == application_id:
            matching_clients.append(client)
    if not matching_clients:
        raise ConfigError(f"No Firebase Android client matches applicationId {application_id!r}")

    android_fingerprints: set[str] = set()
    web_client_ids: set[str] = set()
    for client in matching_clients:
        for oauth in client.get("oauth_client", []) or []:
            if not isinstance(oauth, dict):
                continue
            client_type = str(oauth.get("client_type", ""))
            client_id = str(oauth.get("client_id", "")).strip()
            if client_type == "1":
                android_info = oauth.get("android_info", {})
                if not isinstance(android_info, dict) or android_info.get("package_name") != application_id:
                    raise ConfigError("An Android OAuth client does not match the AIRI package name")
                fingerprint = normalize_sha1(str(android_info.get("certificate_hash", "")))
                if not SHA1_RE.fullmatch(fingerprint):
                    raise ConfigError("An Android OAuth client has a missing or malformed SHA-1 certificate fingerprint")
                android_fingerprints.add(fingerprint)
            elif client_type == "3":
                if not WEB_CLIENT_RE.fullmatch(client_id):
                    raise ConfigError("A Web OAuth client ID is missing or malformed")
                if not client_id.startswith(project_number + "-"):
                    raise ConfigError("The Web OAuth client ID belongs to a different Google Cloud project")
                web_client_ids.add(client_id)

    if not android_fingerprints:
        raise ConfigError("No Android OAuth client/fingerprint is registered in google-services.json")
    if not web_client_ids:
        raise ConfigError("No Web OAuth client is present; Credential Manager requires default_web_client_id")

    app_id = str(matching_clients[0].get("client_info", {}).get("mobilesdk_app_id", ""))
    if not app_id.startswith(f"1:{project_number}:android:"):
        raise ConfigError("Firebase mobilesdk_app_id does not belong to the configured project_number")

    return {
        "project_number": project_number,
        "application_id": application_id,
        "android_sha1s": sorted(android_fingerprints),
        "web_client_ids": sorted(web_client_ids),
    }


def validate_expected_sha1(result: dict[str, Any], expected_sha1: str) -> None:
    expected = normalize_sha1(expected_sha1)
    if not SHA1_RE.fullmatch(expected):
        raise ConfigError("--expected-sha1 must contain exactly 40 hexadecimal digits")
    if expected not in result["android_sha1s"]:
        raise ConfigError(
            "The APK signing certificate is not registered for this Firebase Android OAuth client. "
            "Register its SHA-1 in Firebase, download a fresh google-services.json, and retry."
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("app/google-services.json"))
    parser.add_argument("--application-id", default="com.airi.assistant")
    parser.add_argument(
        "--expected-sha1",
        help="Optional signing-certificate SHA-1 (hex or colon-separated) to require in the Android OAuth clients",
    )
    args = parser.parse_args()
    try:
        result = inspect_config(args.config, args.application_id)
        if args.expected_sha1:
            validate_expected_sha1(result, args.expected_sha1)
    except ConfigError as exc:
        print(f"Google OAuth configuration error: {exc}", file=sys.stderr)
        return 1

    print(
        "Google OAuth configuration OK: "
        f"package={result['application_id']}, "
        f"android_oauth_certificates={len(result['android_sha1s'])}, "
        f"web_oauth_clients={len(result['web_client_ids'])}"
    )
    if args.expected_sha1:
        print("The supplied signing certificate is registered.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
