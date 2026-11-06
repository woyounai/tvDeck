#!/usr/bin/env python3
"""Keep the legal notices and versions consistent across both clients."""
import json
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]


class UsageNoticeResourcesTests(unittest.TestCase):
    def test_both_clients_display_the_same_notice_in_each_language(self):
        language_keys = []
        for swift_locale, android_locale in [("en", "values"), ("zh-Hans", "values-zh-rCN")]:
            swift = (ROOT / "iosadb" / f"{swift_locale}.lproj" / "Localizable.strings").read_text()
            pairs = [
                line.removesuffix(";").split(" = ", 1)
                for line in swift.splitlines() if line.startswith('"legal_')
            ]
            ios = {json.loads(key): json.loads(value) for key, value in pairs}
            self.assertEqual(len(pairs), len(ios), "Duplicate legal keys")
            xml = ElementTree.parse(ROOT / "android-client/app/src/main/res" / android_locale / "strings.xml")
            android = {
                item.attrib["name"]: (item.text or "").replace(chr(92) + "'", "'")
                for item in xml.findall("string")
                if item.attrib["name"].startswith("legal_")
            }
            self.assertTrue(ios)
            self.assertEqual(ios, android)
            language_keys.append(set(ios))
        self.assertEqual(*language_keys)

    def test_aerial_confirmation_text_matches_on_both_clients(self):
        for swift_locale, android_locale in [("en", "values"), ("zh-Hans", "values-zh-rCN")]:
            swift = (ROOT / "iosadb" / f"{swift_locale}.lproj" / "Localizable.strings").read_text()
            ios = {}
            for line in swift.splitlines():
                if line.startswith('"text.aerial_consent_'):
                    key, value = line.removesuffix(";").split(" = ", 1)
                    ios[json.loads(key).removeprefix("text.")] = json.loads(value)
            xml = ElementTree.parse(ROOT / "android-client/app/src/main/res" / android_locale / "strings.xml")
            android = {
                item.attrib["name"]: (item.text or "").replace(chr(92) + "'", "'")
                for item in xml.findall("string")
                if item.attrib["name"].startswith("aerial_consent_")
            }
            self.assertTrue(ios)
            self.assertEqual(ios, android)

    def test_clients_use_the_same_consent_version(self):
        swift = (ROOT / "iosadb/Core/UsageConsent.swift").read_text()
        java = (ROOT / "android-client/app/src/main/java/com/tvdeck/client/UsageConsent.java").read_text()
        self.assertEqual(
            re.search(r'static let version = "([^"]+)"', swift).group(1),
            re.search(r'static final String VERSION="([^"]+)"', java).group(1),
        )


if __name__ == "__main__":
    unittest.main()
