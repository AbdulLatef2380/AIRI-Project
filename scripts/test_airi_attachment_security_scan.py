#!/usr/bin/env python3
"""Regression tests for bounded text-attachment source validation."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import airi_attachment_security_scan as scan  # noqa: E402


CURRENT_SOURCE = scan.CHAT_VIEW_MODEL.read_text(encoding="utf-8")


class BoundedAttachmentReadTest(unittest.TestCase):
    def test_current_chat_reader_is_bounded(self) -> None:
        self.assertTrue(scan.has_bounded_text_attachment_read(CURRENT_SOURCE))

    def test_unbounded_reader_is_rejected(self) -> None:
        unbounded = "File(path).bufferedReader().use { it.readText() }"
        self.assertFalse(scan.has_bounded_text_attachment_read(unbounded))

    def test_partial_loop_is_rejected(self) -> None:
        partial = """
        bufferedReader().use { reader ->
            val bounded = StringBuilder()
            while (bounded.length < readLimit) {
                bounded.append(reader.readLine())
            }
        }
        """
        self.assertFalse(scan.has_bounded_text_attachment_read(partial))


if __name__ == "__main__":
    unittest.main()
