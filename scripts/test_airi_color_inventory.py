#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("airi_color_inventory.py")
SPEC = importlib.util.spec_from_file_location("airi_color_inventory", SCRIPT)
assert SPEC and SPEC.loader
INVENTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INVENTORY)


class AiriColorInventoryTest(unittest.TestCase):
    def test_inventory_finds_literals_references_and_excludes_theme(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main/java/com/airi/assistant/ui/screens/Test.kt"
            theme = root / "app/src/main/java/com/airi/assistant/ui/theme/Color.kt"
            source.parent.mkdir(parents=True)
            theme.parent.mkdir(parents=True)
            source.write_text(
                "val a = Color(0xFF123456)\nval b = Color.White\nval c = CosmicAccent\nval d = SemanticError\n",
                encoding="utf-8",
            )
            theme.write_text("val ignored = Color(0xFFFFFFFF)\n", encoding="utf-8")
            report = INVENTORY.inventory(root)
            self.assertEqual(4, report["total"])
            self.assertEqual({"Color": 1, "ColorReference": 1, "ThemeToken": 2}, report["counts"])
            self.assertTrue(all("theme/" not in item["file"] for item in report["entries"]))

    def test_contrast_report_covers_every_theme_mode_and_passes(self):
        report = INVENTORY.contrast_report(Path(__file__).resolve().parents[1])
        self.assertTrue(report["pass"], report)
        self.assertEqual({"DARK", "LIGHT", "SYSTEM", "AMOLED"}, {item["mode"] for item in report["checks"]})
        self.assertGreaterEqual(min(item["ratio"] for item in report["checks"]), 4.5)

    def test_contrast_ratio_is_symmetric(self):
        white = (255, 255, 255)
        black = (0, 0, 0)
        self.assertEqual(INVENTORY.contrast_ratio(white, black), INVENTORY.contrast_ratio(black, white))
        self.assertAlmostEqual(INVENTORY.contrast_ratio(white, black), 21.0, places=2)


if __name__ == "__main__":
    unittest.main()
