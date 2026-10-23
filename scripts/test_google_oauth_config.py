import json
import tempfile
import unittest
from pathlib import Path

from verify_google_oauth_config import (
    ConfigError,
    inspect_config,
    normalize_sha1,
    validate_expected_sha1,
)


PACKAGE = "com.airi.assistant"
SHA1 = "a" * 40
PROJECT_NUMBER = "93237507759"


def valid_config():
    return {
        "project_info": {"project_number": PROJECT_NUMBER, "project_id": "test-project"},
        "client": [
            {
                "client_info": {
                    "mobilesdk_app_id": f"1:{PROJECT_NUMBER}:android:1234567890abcdef",
                    "android_client_info": {"package_name": PACKAGE},
                },
                "oauth_client": [
                    {
                        "client_type": 1,
                        "client_id": f"{PROJECT_NUMBER}-android.apps.googleusercontent.com",
                        "android_info": {"package_name": PACKAGE, "certificate_hash": SHA1},
                    },
                    {
                        "client_type": 3,
                        "client_id": f"{PROJECT_NUMBER}-web.apps.googleusercontent.com",
                    },
                ],
            }
        ],
    }


class GoogleOAuthConfigTest(unittest.TestCase):
    def inspect(self, config):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "google-services.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            return inspect_config(path, PACKAGE)

    def test_valid_firebase_config_exposes_only_oauth_metadata(self):
        result = self.inspect(valid_config())
        self.assertEqual(result["application_id"], PACKAGE)
        self.assertEqual(result["android_sha1s"], [SHA1])
        self.assertEqual(len(result["web_client_ids"]), 1)

    def test_rejects_wrong_android_package(self):
        config = valid_config()
        config["client"][0]["client_info"]["android_client_info"]["package_name"] = "com.other.app"
        with self.assertRaisesRegex(ConfigError, "No Firebase Android client"):
            self.inspect(config)

    def test_rejects_missing_android_certificate(self):
        config = valid_config()
        config["client"][0]["oauth_client"] = [config["client"][0]["oauth_client"][1]]
        with self.assertRaisesRegex(ConfigError, "No Android OAuth client"):
            self.inspect(config)

    def test_rejects_missing_web_client(self):
        config = valid_config()
        config["client"][0]["oauth_client"] = [config["client"][0]["oauth_client"][0]]
        with self.assertRaisesRegex(ConfigError, "No Web OAuth client"):
            self.inspect(config)

    def test_rejects_malformed_android_sha1(self):
        config = valid_config()
        config["client"][0]["oauth_client"][0]["android_info"]["certificate_hash"] = "bad"
        with self.assertRaisesRegex(ConfigError, "malformed SHA-1"):
            self.inspect(config)

    def test_rejects_wrong_signed_apk_certificate(self):
        result = self.inspect(valid_config())
        with self.assertRaisesRegex(ConfigError, "APK signing certificate is not registered"):
            validate_expected_sha1(result, "b" * 40)

    def test_accepts_colon_separated_signed_apk_certificate(self):
        result = self.inspect(valid_config())
        validate_expected_sha1(result, ":".join(["AA"] * 20))

    def test_sha1_normalization_accepts_colons_case_insensitively(self):
        self.assertEqual(normalize_sha1(":".join(["AA"] * 20)), "a" * 40)


if __name__ == "__main__":
    unittest.main()
