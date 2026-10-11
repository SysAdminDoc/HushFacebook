"""Tests for tools/release_text.py, each on a small repository written to a temporary folder.

Run from the repository root: py -3.13 -I -m unittest discover -s tools -p "test_*.py"
"""

import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import release_text  # noqa: E402

REPO = HERE.parent

CHANGELOG = """# Changelog

Every change, newest first.

## Unreleased

### Added

* **Facebook:** New patch, `Hide x`, with a switch under **News feed**.

### Changed

* **Facebook - Reels:** Reels open on the **first** frame.
* **Tooling:** The gate runs twice.

## 0.1.0 (2026-01-02)

* **Facebook:** The first release.

### Added

* **Facebook:** Something.
"""

README = """# Hushfacebook

  <a href="https://github.com/SysAdminDoc/Hushfacebook/releases"><img src="https://img.shields.io/badge/version-0.1.0-0866FF" alt="Version 0.1.0"></a>

The latest release is [v0.1.0](https://github.com/SysAdminDoc/Hushfacebook/releases/tag/v0.1.0), with 2 patches. It includes all changes since v0.0.9, among them something, as the [changelog](CHANGELOG.md) describes.

## Install
"""

FORM = """name: Bug report
body:
  - type: input
    attributes:
      label: Hushfacebook version
      placeholder: Version 0.1.0 for Facebook 580.0.0.51.74
  - type: input
    attributes:
      placeholder: Morphe Manager 1.34.0
"""


def target(version, arm64, armv7=None):
    codes = {"ARM64_V8A": arm64}
    if armv7:
        codes["ARMEABI_V7A"] = armv7
    return {"version": version, "experimental": False, "versionCodes": codes}


def patch_list(*targets):
    compatibility = [{"name": "Facebook", "packageName": "com.facebook.katana", "targets": list(targets)}]
    return {"version": "v0.1.0", "patches": [
        {"name": "One", "compatibility": compatibility},
        {"name": "Two", "compatibility": compatibility},
        {"name": "Three", "compatibility": compatibility},
    ]}


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


class Repo(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        write(self.root / "CHANGELOG.md", CHANGELOG)
        write(self.root / "README.md", README)
        write(self.root / "gradle.properties", "org.gradle.jvmargs = -Xmx2g\nversion = 0.1.0\n")
        write(self.root / "gradle" / "libs.versions.toml", '[versions]\nmanager-floor = "1.34.0"\n')
        write(self.root / ".github" / "ISSUE_TEMPLATE" / "bug_report.yml", FORM)
        self.declare(target("581.0.0.45.58", 475215365, 475215364), target("580.0.0.51.74", 475019344))
        write(self.root / "patches-bundle.json", json.dumps({
            "created_at": "2026-01-02T10:00:00",
            "description": "Hushfacebook v0.1.0 targets Facebook 580.0.0.51.74.",
            "download_url": "https://github.com/SysAdminDoc/Hushfacebook/releases/download/v0.1.0/patches-0.1.0.mpp",
            "signature_download_url": "",
            "version": "0.1.0",
        }, indent=2) + "\n")

    def tearDown(self):
        self.scratch.cleanup()

    def declare(self, *targets):
        write(self.root / "patches-list.json", json.dumps(patch_list(*targets), indent=2))

    def text(self, name):
        return (self.root / name).read_text(encoding="utf-8")

    def refused(self, call, *needles):
        snapshot = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(SystemExit) as stop:
            call()
        for needle in needles:
            self.assertIn(needle, str(stop.exception))
        after = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(snapshot, after, "a refused command changed a file")
        return str(stop.exception)

    def cut(self, summary="Two new switches for Facebook 581.0.0.45.58.", date="2026-02-03"):
        return release_text.cut(self.root, "0.2.0", summary, date)


class CutTest(Repo):
    def test_renames_unreleased_and_puts_the_summary_first(self):
        self.cut()
        text = self.text("CHANGELOG.md")
        self.assertNotIn("## Unreleased", text)
        self.assertIn("## 0.2.0 (2026-02-03)\n\n* **Facebook:** Two new switches for Facebook 581.0.0.45.58.\n\n"
                      "### Added\n\n* **Facebook:** New patch", text)
        self.assertIn("## 0.1.0 (2026-01-02)", text)
        self.assertTrue(text.startswith("# Changelog\n\nEvery change, newest first.\n\n## 0.2.0"))

    def test_refuses_a_version_that_is_already_there(self):
        self.refused(lambda: release_text.cut(self.root, "0.1.0", "x", "2026-02-03"), "already has 0.1.0")

    def test_refuses_an_unscoped_or_wrapped_bullet(self):
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("* **Tooling:** The gate runs twice.",
                                                            "* **Compatibility:** Facebook 582."))
        self.refused(self.cut, "isn't scoped")
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("The gate runs twice.", "The gate runs\n  twice."))
        self.refused(self.cut, "more than one line")

    def test_refuses_a_bullet_above_the_first_heading_and_a_dash(self):
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("## Unreleased\n", "## Unreleased\n\n* **Facebook:** Loose.\n"))
        self.refused(self.cut, "outside a ### heading")
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("runs twice", "runs twice — always"))
        self.refused(self.cut, "dash")

    def test_refuses_a_summary_written_as_a_bullet_and_a_bad_date(self):
        self.refused(lambda: self.cut(summary="* **Facebook:** Hello."), "bullet's text alone")
        self.refused(lambda: self.cut(date="2026-13-01"), "isn't a date")

    def test_refuses_a_changelog_with_nothing_unreleased(self):
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("## Unreleased", "## 0.1.1 (2026-01-03)"))
        self.refused(self.cut, "no ## Unreleased")


class BumpTest(Repo):
    def test_moves_the_version_and_the_badge(self):
        release_text.bump(self.root, "0.2.0")
        self.assertIn("version = 0.2.0\n", self.text("gradle.properties"))
        self.assertIn("org.gradle.jvmargs = -Xmx2g\n", self.text("gradle.properties"))
        readme = self.text("README.md")
        self.assertIn("badge/version-0.2.0-0866FF", readme)
        self.assertIn('alt="Version 0.2.0"', readme)
        # The latest-release sentence moves with the index, not with the source.
        self.assertIn("The latest release is [v0.1.0]", readme)

    def test_refuses_the_same_or_an_older_version(self):
        self.refused(lambda: release_text.bump(self.root, "0.1.0"), "already says 0.1.0")
        self.refused(lambda: release_text.bump(self.root, "0.0.9"), "isn't newer")


class NotesTest(Repo):
    CHECKED = ("Local checks passed. All 3 patches applied without forcing to Facebook 581.0.0.45.58 (both builds) "
               "and 580.0.0.51.74.")

    def notes(self, checked=CHECKED, draft=False):
        return release_text.github_notes(self.root, "0.2.0", checked, draft)[0]

    def test_keeps_every_bullet_and_takes_the_labels_off(self):
        self.cut()
        notes = self.notes()
        self.assertTrue(notes.startswith("Two new switches for Facebook 581.0.0.45.58.\n\nNew in this release\n\n"
                                         "- New patch, `Hide x`, with a switch under `News feed`.\n\nOther changes\n\n"
                                         "- Reels open on the `first` frame.\n\nBehind the scenes\n\n"
                                         "- The gate runs twice.\n\nThe [changelog]"))
        self.assertNotIn("**", notes)
        self.assertNotIn("Facebook:", notes)
        self.assertIn("2. Patch Facebook `581.0.0.45.58`. On most phones that's build `475215365`, the arm64-v8a "
                      "one. Phones with a 32-bit processor take build `475215364`, the armeabi-v7a one. Facebook "
                      "`580.0.0.51.74` (build `475019344`) still works.", notes)
        self.assertIn("1. Update Morphe Manager to 1.34.0 or newer", notes)
        self.assertTrue(notes.endswith("What was checked\n\n" + self.CHECKED + "\n"))
        self.assertEqual(release_text.target_in(notes)[0], "581.0.0.45.58")

    def test_the_update_check_reads_the_newest_build(self):
        self.cut()
        older = "All 3 patches applied to Facebook 580.0.0.51.74 and later to 581.0.0.45.58 too."
        self.assertEqual(release_text.target_in(self.notes(checked=older))[0], "581.0.0.45.58")
        self.refused(lambda: self.notes(checked="All 3 patches applied to Facebook 580.0.0.51.74."),
                     "read Facebook 580.0.0.51.74", "It's 581.0.0.45.58")

    def test_an_earlier_sentence_wins_over_the_checked_one(self):
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("Reels open on", "This targets Facebook 580.0.0.51.74. Reels open on"))
        self.cut()
        self.refused(self.notes, "read Facebook 580.0.0.51.74")

    def test_a_draft_needs_no_target_but_a_release_does(self):
        self.cut()
        self.assertNotIn("What was checked", self.notes(checked=None, draft=True))
        self.refused(lambda: self.notes(checked=None), "--checked")
        self.refused(lambda: self.notes(checked="Local checks passed."), "nothing in the notes says")

    def test_refuses_a_section_cut_did_not_write(self):
        self.refused(self.notes, "no dated 0.2.0 heading")
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("## Unreleased\n", "## 0.2.0 (2026-02-03)\n"))
        self.refused(self.notes, "opens with 0 bullets")

    def test_refuses_a_heading_it_has_no_place_for(self):
        write(self.root / "CHANGELOG.md", CHANGELOG.replace("### Changed", "### Security"))
        self.cut()
        self.refused(self.notes, "### Security")

    def test_maps_reported_issues_after_the_changes(self):
        write(self.root / "CHANGELOG.md", CHANGELOG.replace(
            "## 0.1.0", "### Reported issues\n\n* **Facebook:** #12 Reels froze: fixed, see Other changes.\n\n## 0.1.0"))
        self.cut()
        notes = self.notes()
        self.assertIn("- Reels open on the `first` frame.\n\nReported issues\n\n"
                      "- #12 Reels froze: fixed, see Other changes.\n\nBehind the scenes\n\n", notes)

    def test_refuses_a_dash_in_the_checked_paragraph(self):
        self.cut()
        self.refused(lambda: self.notes(checked=self.CHECKED + " Done – all of it."), "dash")


class IndexTest(Repo):
    def index(self, summary="Two new switches and faster Reels.", readme="two new switches", created="2026-02-03T17:04:05",
              version="0.2.0"):
        return release_text.index(self.root, version, created, summary, readme, 2276, 820)

    def test_points_the_index_readme_and_form_at_the_release(self):
        self.index()
        raw = self.text("patches-bundle.json")
        bundle = json.loads(raw)
        self.assertEqual(list(bundle), ["created_at", "description", "download_url", "signature_download_url", "version"])
        self.assertEqual(raw, json.dumps(bundle, indent=2, ensure_ascii=False) + "\n")
        self.assertEqual(bundle["version"], "0.2.0")
        self.assertEqual(bundle["created_at"], "2026-02-03T17:04:05")
        self.assertEqual(bundle["download_url"],
                         "https://github.com/SysAdminDoc/Hushfacebook/releases/download/v0.2.0/patches-0.2.0.mpp")
        self.assertEqual(bundle["description"].split("\n\n"), [
            "Hushfacebook v0.2.0 targets Facebook 581.0.0.45.58 (com.facebook.katana), the newest release, and "
            "580.0.0.51.74 still work.",
            "Two new switches and faster Reels.",
            "Validation: 2276 runtime tests passed locally. All 820 patch tests passed too, along with both Android "
            "lints. All 3 patches applied to Facebook 581.0.0.45.58 and 580.0.0.51.74, and the resource and "
            "injected-code checks passed.",
            "Review diagnostic reports for private data before sharing them.",
            "Needs Morphe Manager 1.34.0 or newer. Patch the arm64-v8a bundle of Facebook 581.0.0.45.58, build "
            "475215365, or its 32-bit build 475215364. Build 580.0.0.51.74 (475019344) still works. The .mpp is a "
            "patch bundle, not a Facebook APK. Keep the manager's existing signing key when updating.",
        ])
        self.assertIn("The latest release is [v0.2.0](https://github.com/SysAdminDoc/Hushfacebook/releases/tag/v0.2.0), "
                      "with 3 patches. It includes all changes since v0.1.0, among them two new switches, as the "
                      "[changelog](CHANGELOG.md) describes.\n", self.text("README.md"))
        self.assertIn("      placeholder: Version 0.2.0 for Facebook 581.0.0.45.58\n", self.text(".github/ISSUE_TEMPLATE/bug_report.yml"))
        self.assertIn("placeholder: Morphe Manager 1.34.0", self.text(".github/ISSUE_TEMPLATE/bug_report.yml"))

    def test_one_target_reads_without_the_older_builds(self):
        self.declare(target("581.0.0.45.58", 475215365))
        self.index()
        description = json.loads(self.text("patches-bundle.json"))["description"]
        self.assertTrue(description.startswith("Hushfacebook v0.2.0 targets Facebook 581.0.0.45.58 "
                                               "(com.facebook.katana), the newest release.\n\n"))
        self.assertIn("Patch the arm64-v8a bundle of Facebook 581.0.0.45.58, build 475215365. The .mpp", description)

    def test_refuses_a_summary_that_names_another_count_or_build(self):
        self.refused(lambda: self.index(summary="It brings 90 patches."), "name 3 patches and Facebook 581.0.0.45.58 alone")
        self.refused(lambda: self.index(summary="Built for Facebook 580.0.0.51.74 first."), "Facebook 580.0.0.51.74")

    def test_refuses_the_published_version_and_a_bad_time(self):
        self.refused(lambda: self.index(version="0.1.0"), "already names 0.1.0")
        self.refused(lambda: self.index(created="2026-02-03T17:04:05Z"), "no Z or offset")
        self.refused(lambda: release_text.index(self.root, "0.2.0", "2026-02-03T17:04:05", "x.", "y", 0, 820), "neither is zero")

    def test_refuses_a_readme_without_one_latest_release_sentence(self):
        write(self.root / "README.md", README.replace("The latest release is", "Our latest release is"))
        self.refused(self.index, "README.md")


class TranslationsTest(unittest.TestCase):
    TABLES = {
        "de": "# German\nBuild %1$s\tBuild %1$s\nHide ads\tWerbung ausblenden\n",
        "tr": "# Turkish\nBuild %1$s\tSürüm %1$s\nHide ads\tReklamları gizle\n",
    }

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        (self.root / "scripts").mkdir()
        shutil.copy(REPO / "scripts" / "gen-l10n.py", self.root / "scripts" / "gen-l10n.py")
        for language, table in self.TABLES.items():
            write(self.root / "extensions/shared/library/src/main/l10n" / f"{language}.tsv", table)
        self.java().parent.mkdir(parents=True)
        self.generate()

    def tearDown(self):
        self.scratch.cleanup()

    def generate(self):
        subprocess.run([sys.executable, "-I", str(self.root / "scripts" / "gen-l10n.py")], check=True,
                       capture_output=True)

    def java(self):
        return self.root / "extensions/shared/library/src/main/java/app/morphe/extension/shared/L10nTranslations.java"

    def test_current_tables_pass(self):
        self.assertIn("2 tables translate the same 2 strings", release_text.check_translations(self.root))

    def test_a_table_missing_a_row_fails(self):
        write(self.root / "extensions/shared/library/src/main/l10n/tr.tsv", "Build %1$s\tSürüm %1$s\n")
        self.generate()
        with self.assertRaises(SystemExit) as stop:
            release_text.check_translations(self.root)
        self.assertIn("tr lacks 1: Hide ads", str(stop.exception))

    def test_a_plural_variant_is_not_a_missing_row(self):
        tables = self.root / "extensions/shared/library/src/main/l10n"
        write(tables / "de.tsv", self.TABLES["de"] + "%1$d items\t%1$d Einträge\n")
        write(tables / "tr.tsv", self.TABLES["tr"] + "%1$d items\t%1$d öge\n%1$d items|few\t%1$d öge\n")
        self.generate()
        self.assertIn("translate the same 3 strings", release_text.check_translations(self.root))

    def test_a_table_edited_without_the_generator_fails(self):
        write(self.root / "extensions/shared/library/src/main/l10n/de.tsv", self.TABLES["de"].replace("ausblenden", "verbergen"))
        with self.assertRaises(SystemExit) as stop:
            release_text.check_translations(self.root)
        self.assertIn("isn't what scripts/gen-l10n.py makes", str(stop.exception))
        self.assertNotIn("verbergen", self.java().read_text(encoding="ascii"), "the check rewrote the class")

    def test_a_table_the_generator_refuses_fails(self):
        write(self.root / "extensions/shared/library/src/main/l10n/de.tsv", self.TABLES["de"] + "Hide ads\tNochmal\n")
        with self.assertRaises(SystemExit) as stop:
            release_text.check_translations(self.root)
        self.assertIn("duplicate entry", str(stop.exception))

    def test_the_repository_tables_are_current(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertIn("is current", release_text.check_translations(REPO))


class CommandLineTest(Repo):
    def run_tool(self, *args):
        return subprocess.run([sys.executable, "-I", str(HERE / "release_text.py"), "--root", str(self.root), *args],
                              capture_output=True, text=True, encoding="utf-8")

    def test_runs_the_release_text_in_order(self):
        summary = self.root / "summary.txt"
        write(summary, "Two new switches for Facebook 581.0.0.45.58.\n")
        self.assertEqual(self.run_tool("cut", "--version", "0.2.0", "--summary", str(summary)).returncode, 0)
        self.assertEqual(self.run_tool("bump", "--version", "0.2.0").returncode, 0)
        checked = self.root / "checked.txt"
        write(checked, NotesTest.CHECKED + "\n")
        out = self.root / "notes.md"
        done = self.run_tool("github-notes", "--version", "0.2.0", "--checked", str(checked), "--out", str(out))
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("target Facebook 581.0.0.45.58", done.stdout)
        self.assertIn("What was checked", out.read_text(encoding="utf-8"))
        write(self.root / "readme.txt", "two new switches\n")
        write(self.root / "bundle.txt", "Two new switches and faster Reels.\n")
        done = self.run_tool("index", "--version", "0.2.0", "--created", "2026-02-03T17:04:05", "--bundle-summary",
                             str(self.root / "bundle.txt"), "--readme-summary", str(self.root / "readme.txt"),
                             "--runtime", "10", "--patch", "20")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(json.loads(self.text("patches-bundle.json"))["version"], "0.2.0")

    def test_a_refusal_exits_non_zero_with_the_reason(self):
        summary = self.root / "summary.txt"
        write(summary, "First line.\nSecond line.\n")
        done = self.run_tool("cut", "--version", "0.2.0", "--summary", str(summary))
        self.assertNotEqual(done.returncode, 0)
        self.assertIn("one paragraph on one line", done.stderr)
        self.assertIn("## Unreleased", self.text("CHANGELOG.md"))

    def test_files_stay_lf(self):
        summary = self.root / "summary.txt"
        write(summary, "Two new switches.\r\n")
        self.assertEqual(self.run_tool("cut", "--version", "0.2.0", "--summary", str(summary)).returncode, 0)
        self.assertNotIn(b"\r", (self.root / "CHANGELOG.md").read_bytes())


if __name__ == "__main__":
    unittest.main()
