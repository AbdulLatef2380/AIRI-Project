#!/usr/bin/env python3
"""Append missing Android string resources from the default locale as explicit fallbacks."""

from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from copy import deepcopy
from pathlib import Path


def read_resources(path: Path) -> ET.ElementTree:
    return ET.parse(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    args = parser.parse_args()

    source_tree = read_resources(args.source)
    target_tree = read_resources(args.target)
    source_root = source_tree.getroot()
    target_root = target_tree.getroot()

    target_names = {element.attrib.get("name") for element in target_root.findall("string")}
    missing = [element for element in source_root.findall("string") if element.attrib.get("name") not in target_names]
    if not missing:
        print(f"No missing resources in {args.target}")
        return

    target_root.append(ET.Comment(" English fallbacks pending native-language review. "))
    for source_element in missing:
        target_root.append(deepcopy(source_element))

    ET.indent(target_tree, space="    ")
    target_tree.write(args.target, encoding="utf-8", xml_declaration=True)
    print(f"Added {len(missing)} English fallback resources to {args.target}")


if __name__ == "__main__":
    main()
