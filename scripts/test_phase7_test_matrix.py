#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("phase7_test_matrix.py")
spec = importlib.util.spec_from_file_location("phase7_test_matrix", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


class Phase7RuntimeEvidenceTest(unittest.TestCase):
    def write_report(self, root: Path, name: str, xml: str) -> None:
        target = root / "test-results" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(xml, encoding="utf-8")

    def test_missing_evidence_is_not_verified(self):
        status, detail = module.runtime_status(
            ["app/src/test/java/com/airi/assistant/FooTest.kt"],
            [],
            "runtime evidence root was not supplied",
        )
        self.assertEqual(status, "NOT_VERIFIED")
        self.assertIn("not supplied", detail["reason"])

    def test_matching_clean_junit_report_is_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(
                root,
                "TEST-FooTest.xml",
                '<testsuite><testcase classname="com.airi.FooTest" name="works"/></testsuite>',
            )
            reports, error = module.junit_evidence(root)
            status, detail = module.runtime_status(
                ["app/src/test/java/com/airi/FooTest.kt"], reports, error
            )
            self.assertEqual(status, "PASS")
            self.assertEqual(detail["tests"], 1)

    def test_failure_in_matching_junit_report_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(
                root,
                "TEST-FooTest.xml",
                '<testsuite><testcase classname="com.airi.FooTest" name="fails"><failure/></testcase></testsuite>',
            )
            reports, error = module.junit_evidence(root)
            status, detail = module.runtime_status(
                ["app/src/test/java/com/airi/FooTest.kt"], reports, error
            )
            self.assertEqual(status, "BLOCKED")
            self.assertEqual(detail["failures"], 1)

    def test_empty_junit_report_is_not_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, "empty.xml", "<testsuite tests=\"0\"/>")
            reports, error = module.junit_evidence(root)
            status, _ = module.runtime_status(
                ["app/src/test/java/com/airi/FooTest.kt"], reports, error
            )
            self.assertEqual(status, "NOT_VERIFIED")


if __name__ == "__main__":
    unittest.main()
