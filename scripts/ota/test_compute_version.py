import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compute_version as cv  # noqa: E402

GRADLE = '''val configuredVersionCode = configuredValue("JARVIS_VERSION_CODE")?.toIntOrNull() ?: 144
val configuredVersionName = configuredValue("JARVIS_VERSION_NAME")?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: "1.8.8"
'''


def parts(name):
    return tuple(int(p) for p in name.split("."))


class ComputeVersionTest(unittest.TestCase):
    def test_base_version_read_from_multiline_gradle_expression(self):
        self.assertEqual(cv.base_version(GRADLE), "1.8.8")

    def test_missing_base_is_an_error(self):
        with self.assertRaises(ValueError):
            cv.base_version("val x = 1")

    def test_code_is_strictly_increasing_with_run_number(self):
        codes = [cv.compute(GRADLE, n, "Antigravity")["code"] for n in (1, 2, 1752, 1753, 99999)]
        self.assertEqual(codes, sorted(set(codes)))

    def test_every_code_is_newer_than_the_installed_production_build(self):
        for n in (1, 5, 1752):
            self.assertGreater(cv.compute(GRADLE, n, "Antigravity")["code"], cv.LEGACY_MAX_CODE)

    def test_manual_and_ota_builds_share_the_scheme_so_the_later_run_always_wins(self):
        manual = cv.compute(GRADLE, 1760, "Antigravity")
        ota = cv.compute(GRADLE, 1761, "Antigravity")
        self.assertGreater(ota["code"], manual["code"])

    def test_name_reads_newer_than_installed_1_8_8(self):
        name = cv.compute(GRADLE, 1752, "Antigravity")["name"]
        self.assertGreater(parts(name), parts("1.8.8"))
        self.assertTrue(name.startswith("1.8.8."))

    def test_tag_and_asset_follow_the_server_rule(self):
        result = cv.compute(GRADLE, 1752, "Antigravity")
        self.assertEqual(result["tag"], "ota-antigravity-11752")
        self.assertEqual(result["asset"], "jarvis-antigravity-11752.apk")

    def test_slug_for_slashes_and_symbols(self):
        self.assertEqual(cv.branch_slug("Feature/New_Thing!"), "feature-new-thing")
        self.assertEqual(cv.branch_slug("///"), "branch")

    def test_bad_run_numbers_rejected(self):
        for bad in ("0", "-3", "abc", "", "12345678901"):
            with self.assertRaises(ValueError):
                cv.compute(GRADLE, bad, "Antigravity")


if __name__ == "__main__":
    unittest.main()
