#!/usr/bin/env python3
"""Reports likely untranslated Android string resources without mutating source files."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "app" / "src" / "main" / "res"
LOCALES = ("values-ar", "values-es", "values-zh")
TECHNICAL_RESOURCE_NAMES = {
    "ui_value",
    "stat_breakdown_kv_value",
    "settings_zapier_ifttt",
    "premium_title",
    "brave_search_api",
    "payment_airi_premium",
    "welcome_app_name",
    "default_web_client_id",
    "about_privacy_url",
    "about_stack_body",
    "secret_manager_key_placeholder",
    "zapier_webhook_placeholder",
}
TECHNICAL_VALUES = {
    "AIRI",
    "AIRI Desktop",
    "GitHub",
    "Firebase",
    "JSON",
    "PDF",
    "API",
    "OAuth",
    "PKCE",
    "OpenAI",
    "Gemini",
    "Anthropic",
    "Zapier",
    "IFTTT",
    "Telegram",
    "Notion",
    "Android SpeechRecognizer",
    "STT",
    "TTS",
}


def string_values(path: Path) -> dict[str, str]:
    root = ElementTree.parse(path).getroot()
    return {
        node.attrib["name"]: "".join(node.itertext()).strip()
        for node in root.findall("string")
        if node.attrib.get("name") and node.attrib.get("translatable", "true") != "false"
    }


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def is_reportable(name: str, value: str) -> bool:
    compact = normalize(value)
    if name in TECHNICAL_RESOURCE_NAMES or len(compact) < 12 or compact in TECHNICAL_VALUES:
        return False
    if "%" in compact and re.fullmatch(r"[A-Z0-9_ .:%/-]+", compact):
        return False
    return bool(re.search(r"[A-Za-z]", compact)) and not name.startswith(("model_", "provider_"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true", help="return non-zero when likely untranslated values remain")
    args = parser.parse_args()

    default_values = string_values(RESOURCE_ROOT / "values" / "strings.xml")
    findings: list[tuple[str, str, str]] = []
    for locale in LOCALES:
        localized = string_values(RESOURCE_ROOT / locale / "strings.xml")
        for name, default_value in default_values.items():
            localized_value = localized.get(name)
            if localized_value is None:
                continue
            if normalize(localized_value) == normalize(default_value) and is_reportable(name, default_value):
                findings.append((locale, name, localized_value))

    for locale, name, value in findings:
        print(f"[REVIEW] {locale}: {name} = {value}")
    print(f"[INFO] likely_untranslated_values={len(findings)}")
    if args.strict and findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
