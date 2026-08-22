#!/usr/bin/env python3
"""Generate, but do not apply, reviewed localization candidates for AIRI resources."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

from openai import OpenAI

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "build"
MODEL = "gpt-5-mini"
LOCALES = {"values-es": "Spanish (Spain)", "values-zh": "Simplified Chinese"}
MANUAL_OVERRIDES = {
    "values-es": {
        "porcupine_access_key_label": "Clave de acceso de Picovoice",
        "brave_search_title": "API de búsqueda de Brave",
        "voice_access_key_label": "Clave de acceso de Picovoice",
        "voice_porcupine_legacy": "Porcupine (heredado)",
    },
    "values-zh": {
        "porcupine_access_key_label": "Picovoice 访问密钥",
        "brave_search_title": "Brave 搜索 API",
        "voice_access_key_label": "Picovoice 访问密钥",
        "voice_porcupine_legacy": "Porcupine（旧版）",
    },
}
TECHNICAL_RESOURCE_NAMES = {
    "ui_value", "stat_breakdown_kv_value", "settings_zapier_ifttt", "premium_title",
    "brave_search_api", "payment_airi_premium", "welcome_app_name", "default_web_client_id",
    "about_privacy_url", "about_stack_body", "secret_manager_key_placeholder", "zapier_webhook_placeholder",
}
TECHNICAL_VALUES = {"AIRI", "AIRI Desktop", "GitHub", "Firebase", "JSON", "PDF", "API", "OAuth", "PKCE", "OpenAI", "Gemini", "Anthropic", "Zapier", "IFTTT", "Telegram", "Notion", "Android SpeechRecognizer", "STT", "TTS"}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")


def values(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {
        e.attrib["name"]: "".join(e.itertext()).strip()
        for e in root.findall("string")
        if e.attrib.get("name") and e.attrib.get("translatable", "true") != "false"
    }


def reportable(name: str, value: str) -> bool:
    compact = re.sub(r"\s+", " ", value).strip()
    if name in TECHNICAL_RESOURCE_NAMES or len(compact) < 12 or compact in TECHNICAL_VALUES:
        return False
    if "%" in compact and re.fullmatch(r"[A-Z0-9_ .:%/-]+", compact):
        return False
    return bool(re.search(r"[A-Za-z]", compact)) and not name.startswith(("model_", "provider_"))


def candidate_items(locale: str) -> dict[str, str]:
    base = values(RES / "values" / "strings.xml")
    translated = values(RES / locale / "strings.xml")
    return {
        name: source for name, source in base.items()
        if translated.get(name) == source and reportable(name, source)
    }


def translate(client: OpenAI, language: str, items: dict[str, str]) -> dict[str, str]:
    prompt = (
        "Translate Android UI string resource values from English into " + language + ". "
        "Return JSON only as {\"translations\":[{\"key\":\"resource_key\",\"value\":\"translated value\"}]}. "
        "Do not translate names, product names, provider names, URLs, API/JSON/OAuth acronyms, or code-like tokens. "
        "Preserve every printf placeholder (%1$s, %d, %% etc.) exactly, preserve XML-safe plain text (do not add tags), "
        "and keep the tone concise and professional. Translate all values; do not omit or add keys.\n\n"
        + json.dumps(items, ensure_ascii=False, separators=(",", ":"))
    )
    response = client.chat.completions.create(
        model=MODEL,
        messages=[
            {"role": "system", "content": "You are a precise software localization translator. Output valid JSON only."},
            {"role": "user", "content": prompt},
        ],
        response_format={
            "type": "json_schema",
            "json_schema": {
                "name": "localized_resources",
                "strict": True,
                "schema": {
                    "type": "object",
                    "properties": {
                        "translations": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "key": {"type": "string"},
                                    "value": {"type": "string"}
                                },
                                "required": ["key", "value"],
                                "additionalProperties": False
                            }
                        }
                    },
                    "required": ["translations"],
                    "additionalProperties": False
                },
            },
        },
    )
    payload = json.loads(response.choices[0].message.content)
    return {entry["key"]: entry["value"] for entry in payload["translations"]}


def validate(source: dict[str, str], translated: dict[str, str]) -> list[str]:
    issues: list[str] = []
    if set(source) != set(translated):
        issues.append("key set mismatch")
    for key, source_value in source.items():
        candidate = translated.get(key, "")
        if not candidate.strip():
            issues.append(f"{key}: empty translation")
        if PLACEHOLDER.findall(source_value) != PLACEHOLDER.findall(candidate):
            issues.append(f"{key}: placeholder mismatch")
        if candidate == source_value:
            issues.append(f"{key}: unchanged")
    return issues


def main() -> int:
    client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"), base_url=os.environ.get("OPENAI_API_BASE"))
    OUT.mkdir(parents=True, exist_ok=True)
    for locale, language in LOCALES.items():
        source = candidate_items(locale)
        translated = translate(client, language, source)
        translated.update(MANUAL_OVERRIDES.get(locale, {}))
        issues = validate(source, translated)
        out = OUT / f"localization_candidates_{locale.removeprefix('values-')}.json"
        out.write_text(json.dumps({"locale": locale, "items": translated, "validationIssues": issues}, ensure_ascii=False, indent=2) + "\n")
        print(f"{locale}: candidates={len(translated)} validationIssues={len(issues)} output={out}")
        if issues:
            print("\n".join(issues[:30]), file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
