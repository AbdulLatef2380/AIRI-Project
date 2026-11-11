#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("airi_ui_hardcoded_text_lint.py")
SPEC = importlib.util.spec_from_file_location("airi_ui_hardcoded_text_lint", SCRIPT)
assert SPEC and SPEC.loader
LINT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LINT)


class AiriUiHardcodedTextLintTest(unittest.TestCase):
    def test_detects_supported_user_facing_call_sites(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main/java/com/airi/assistant/ui/screens/Test.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                '\n'.join(
                    [
                        'Text("Save")',
                        'Icon(imageVector = Icons.Default.Close, contentDescription = "Close")',
                        'scope.launch { host.showSnackbar("Could not save") }',
                        'Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()',
                    ]
                ),
                encoding="utf-8",
            )
            self.assertEqual({str(source.relative_to(root)): 4}, LINT.scan(root))

    def test_ignores_templates_without_literal_words_symbols_and_suppressed_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main/java/com/airi/assistant/ui/Test.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                '\n'.join(
                    [
                        'Text("$count")',
                        'Text("•")',
                        'Text("protocol_marker") // i18n-ok: protocol token',
                        'Icon(contentDescription = "$id")',
                    ]
                ),
                encoding="utf-8",
            )
            self.assertEqual({}, LINT.scan(root))

    def test_baseline_only_allows_existing_count_and_rejects_growth_or_new_file(self):
        baseline = {"old.kt": 2}
        self.assertEqual({}, LINT.check({"old.kt": 2}, baseline))
        self.assertEqual({"old.kt": (3, 2)}, LINT.check({"old.kt": 3}, baseline))
        self.assertEqual({"new.kt": (1, 0)}, LINT.check({"new.kt": 1}, baseline))
        self.assertEqual({}, LINT.check({"old.kt": 1}, baseline))


if __name__ == "__main__":
    unittest.main()
