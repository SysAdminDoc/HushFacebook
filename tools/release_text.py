"""Release text for Hushfacebook: the CHANGELOG cut, the translation check, GitHub notes and the index.

  py -3.13 tools/release_text.py cut --version 0.9.0 --summary FILE [--date 2026-10-09]
  py -3.13 tools/release_text.py bump --version 0.9.0
  py -3.13 tools/release_text.py check-translations
  py -3.13 tools/release_text.py github-notes --version 0.9.0 --checked FILE --out FILE
  py -3.13 tools/release_text.py github-notes --version 0.9.0 --draft --out FILE
  py -3.13 tools/release_text.py index --version 0.9.0 --created 2026-10-09T17:03:42
        --bundle-summary FILE --readme-summary FILE --runtime N --patch N

Each command checks its inputs and writes nothing when a check fails. scripts/release/release.ps1
runs them in order, and each one can also run alone.

cut          Renames "## Unreleased" to "## X.Y.Z (date)" and puts the summary first as a
             Facebook bullet, which Morphe Manager shows and the GitHub notes open with.
bump         The version in gradle.properties and the README's version badge.
check-translations
             Every table in extensions/shared/library/src/main/l10n translates the same strings,
             and L10nTranslations.java is what scripts/gen-l10n.py makes of them.
github-notes The notes for a GitHub release, from the whole CHANGELOG section: every bullet kept,
             the scope label taken off, bold turned into backticks, Tooling bullets under
             "Behind the scenes". The in-app update check (ReleaseCheck.java) reads the Facebook
             build a release targets from the notes, so the first sentence it would read has to
             name the newest declared build.
index        patches-bundle.json, the README's latest-release sentence and the bug form's version
             placeholder, for the release that was just published.
"""

import argparse
import contextlib
import datetime
import importlib.util
import io
import json
import re
import tempfile
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "SysAdminDoc/Hushfacebook"
PACKAGE = "com.facebook.katana"
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
DASHES = ("—", "–")
# Morphe Manager's scope: "Facebook", or "Facebook - <area>", and the development-only "Tooling".
BULLET = re.compile(r"^\* \*\*(?P<scope>Facebook(?: - [^*]+)?|Tooling):\*\* (?P<text>\S.*)$")
GROUPS = {"Added": "New in this release", "Fixed": "Fixes", "Changed": "Other changes", "Removed": "Removed",
          "Reported issues": "Reported issues"}
TOOLING_GROUP = "Behind the scenes"
ABI_NAMES = {"ARM64_V8A": "arm64-v8a", "ARMEABI_V7A": "armeabi-v7a", "X86_64": "x86_64", "X86": "x86"}

# ReleaseCheck.java's reading of release notes, kept in step with it: the first sentence holding a
# target phrase counts, and the newest Facebook version in it.
TARGET_PHRASE = re.compile(r"(?i)\b(?:targets?|applied\b[^.\n]{0,60}?\bto)\s+Facebook\s+(?=\d)")
FACEBOOK_VERSION = re.compile(r"(?<![\d.])\d{1,4}(?:\.\d{1,6}){2,5}(?!\d)")
SENTENCE_END = re.compile(r"\.(?=\s|$)|\n")


def fail(message: str) -> NoReturn:
    raise SystemExit(f"[release] {message}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def read_paragraph(path: str, what: str) -> str:
    text = read(Path(path)).strip()
    if not text:
        fail(f"{what} is empty: {path}")
    if "\n" in text:
        fail(f"{what} is one paragraph on one line: {path}")
    if any(d in text for d in DASHES):
        fail(f"{what} has an em or en dash: {path}")
    return text


def version_key(version: str) -> tuple:
    return tuple(int(part) for part in version.split("."))


def spoken(items: list[str]) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


def section_bounds(text: str, heading: str) -> tuple[int, int] | None:
    """Where the body under the "## <heading>" line starts and ends, or None."""
    match = re.search(rf"^## {re.escape(heading)}\n", text, re.MULTILINE)
    if not match:
        return None
    following = re.search(r"^## ", text[match.end():], re.MULTILINE)
    return match.end(), match.end() + following.start() if following else len(text)


def parse_section(body: str, name: str) -> tuple[list[str], list[tuple[str, str, str]]]:
    """The bullets before the first ### heading, and (heading, scope, text) for every other bullet."""
    loose, grouped = [], []
    heading = None
    for line in body.split("\n"):
        if not line.strip():
            continue
        if line.startswith("### "):
            heading = line[4:].strip()
            continue
        bullet = BULLET.match(line)
        if not bullet:
            if line.startswith("* "):
                fail(f"a {name} bullet isn't scoped Facebook or Tooling, so Morphe Manager can't show it: {line[:80]}")
            fail(f"a {name} bullet runs over more than one line, join it: {line[:80]}")
        if any(d in line for d in DASHES):
            fail(f"a {name} bullet has an em or en dash: {line[:80]}")
        if heading is None:
            loose.append(line)
        else:
            grouped.append((heading, bullet.group("scope"), bullet.group("text")))
    return loose, grouped


def cut(root: Path, version: str, summary: str, date: str | None = None) -> str:
    if not VERSION.match(version):
        fail(f"{version} isn't X.Y.Z")
    date = date or datetime.date.today().isoformat()
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        fail(f"{date} isn't a date written YYYY-MM-DD")
    changelog = root / "CHANGELOG.md"
    text = read(changelog)
    if re.search(rf"^## {re.escape(version)}\b", text, re.MULTILINE):
        fail(f"CHANGELOG already has {version}")
    bounds = section_bounds(text, "Unreleased")
    if bounds is None:
        fail("CHANGELOG has no ## Unreleased")
    start, end = bounds
    loose, grouped = parse_section(text[start:end], "Unreleased")
    if loose:
        fail("an Unreleased bullet sits outside a ### heading, put it under one: " + loose[0][:80])
    if not grouped:
        fail("Unreleased has no bullets")
    if summary.startswith("* ") or BULLET.match(summary):
        fail("the summary is the bullet's text alone; cut adds the Facebook label")
    heading = f"## {version} ({date})"
    body = text[start:end].lstrip("\n")
    new = text[: start - len("## Unreleased\n")] + heading + "\n\n* **Facebook:** " + summary + "\n\n" + body + text[end:]
    write(changelog, new)
    return f"{len(grouped)} bullets and the summary under {heading}"


def bump(root: Path, version: str) -> str:
    if not VERSION.match(version):
        fail(f"{version} isn't X.Y.Z")
    properties_path, readme_path = root / "gradle.properties", root / "README.md"
    properties = read(properties_path)
    current = re.search(r"(?m)^version\s*=\s*(\S+)\s*$", properties)
    if not current:
        fail("gradle.properties has no version line")
    if current.group(1) == version:
        fail(f"gradle.properties already says {version}")
    if version_key(version) <= version_key(current.group(1)):
        fail(f"{version} isn't newer than {current.group(1)}")
    properties = swap(properties, r"^version[ \t]*=[ \t]*\S+[ \t]*$", f"version = {version}", "gradle.properties")
    readme = read(readme_path)
    readme = swap(readme, r"img\.shields\.io/badge/version-\d+(?:\.\d+)+-", f"img.shields.io/badge/version-{version}-",
                  "README.md badge")
    readme = swap(readme, r'\balt="Version \d+(?:\.\d+)+"', f'alt="Version {version}"', "README.md badge text")
    write(properties_path, properties)
    write(readme_path, readme)
    return f"gradle.properties and the README badge say {version}, up from {current.group(1)}"


def load_generator(root: Path):
    path = root / "scripts" / "gen-l10n.py"
    if not path.is_file():
        fail(f"no translation generator at {path}")
    spec = importlib.util.spec_from_file_location("hushfacebook_gen_l10n", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.L10N = str(root / "extensions" / "shared" / "library" / "src" / "main" / "l10n")
    return module


def check_translations(root: Path) -> str:
    generator = load_generator(root)
    tables_dir = Path(generator.L10N)
    paths = sorted(tables_dir.glob("*.tsv"))
    if not paths:
        fail(f"no translation tables in {tables_dir}")
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            tables = {path.stem: generator.read(str(path)) for path in paths}
    except SystemExit as stop:
        fail(f"a translation table doesn't read: {stop}")
    # A language with more plural forms than English carries extra "<other form>|few" rows, so the
    # tables are compared by the English each row translates.
    keys = {language: {generator.plural_base(key) for key in table} for language, table in tables.items()}
    every = set().union(*keys.values())
    problems = []
    for language, table in sorted(keys.items()):
        missing = sorted(every - table)
        if missing:
            problems.append(f"{language} lacks {len(missing)}: " + "; ".join(m[:50] for m in missing[:3]))
    if problems:
        fail("a string isn't translated in every table:\n  " + "\n  ".join(problems))
    committed = root / "extensions/shared/library/src/main/java/app/morphe/extension/shared/L10nTranslations.java"
    if not committed.is_file():
        fail(f"no generated translations at {committed}")
    with tempfile.TemporaryDirectory() as scratch:
        generator.JAVA = str(Path(scratch) / "L10nTranslations.java")
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                generator.main()
        except SystemExit as stop:
            fail(f"scripts/gen-l10n.py stopped: {stop}")
        fresh = Path(generator.JAVA).read_bytes()
    if fresh != committed.read_bytes():
        fail("L10nTranslations.java isn't what scripts/gen-l10n.py makes of the tables. Run it and commit the result.")
    return f"{len(tables)} tables translate the same {len(every)} strings, and L10nTranslations.java is current"


def catalog_targets(root: Path) -> tuple[int, list[tuple[str, dict]]]:
    """The patch count and the declared Facebook builds, newest first, with their version codes."""
    data = json.loads(read(root / "patches-list.json"))
    patches = data["patches"] if isinstance(data, dict) else data
    targets = {}
    for patch in patches:
        for app in patch.get("compatibility") or []:
            if app.get("packageName") != PACKAGE:
                continue
            for target in app.get("targets") or []:
                if target.get("version") and not target.get("experimental"):
                    targets[target["version"]] = target.get("versionCodes") or {}
    if not targets:
        fail(f"patches-list.json declares no {PACKAGE} build")
    ordered = sorted(targets.items(), key=lambda item: version_key(item[0]), reverse=True)
    return len(patches), ordered


def manager_floor(root: Path) -> str:
    match = re.search(r'(?m)^\s*manager-floor\s*=\s*"([^"]+)"', read(root / "gradle" / "libs.versions.toml"))
    if not match:
        fail("gradle/libs.versions.toml pins no manager-floor")
    return match.group(1)


def target_in(notes: str) -> tuple[str | None, str | None]:
    """ReleaseCheck.targetIn: the newest Facebook version the first target sentence names, and that sentence."""
    for phrase in TARGET_PHRASE.finditer(notes):
        end = SENTENCE_END.search(notes, phrase.end())
        stop = end.start() if end else len(notes)
        sentence = notes[phrase.end(): min(stop, phrase.end() + 200)]
        found = FACEBOOK_VERSION.findall(sentence)
        if found:
            newest = max(found, key=version_key)
            return newest, notes[phrase.start(): min(stop, phrase.end() + 200)]
    return None, None


def plain(text: str) -> str:
    """A CHANGELOG bullet's text as GitHub notes write it: bold names in backticks."""
    return re.sub(r"\*\*([^*]+)\*\*", r"`\1`", text)


def update_steps(root: Path) -> str:
    _, targets = catalog_targets(root)
    newest, codes = targets[0]
    step = f"2. Patch Facebook `{newest}`."
    if "ARM64_V8A" in codes:
        step += f" On most phones that's build `{codes['ARM64_V8A']}`, the arm64-v8a one."
    if "ARMEABI_V7A" in codes:
        step += f" Phones with a 32-bit processor take build `{codes['ARMEABI_V7A']}`, the armeabi-v7a one."
    for abi, code in codes.items():
        if abi not in ("ARM64_V8A", "ARMEABI_V7A"):
            step += f" Build `{code}` is the {ABI_NAMES.get(abi, abi.lower())} one."
    older = [f"`{version}` (build `{next(iter(c.values()))}`)" if c else f"`{version}`" for version, c in targets[1:]]
    if older:
        step += f" Facebook {spoken(older)} still work{'s' if len(older) == 1 else ''}."
    return "\n".join([
        f"1. Update Morphe Manager to {manager_floor(root)} or newer, then refresh the Hushfacebook source.",
        step,
        "3. Install over your current patched Facebook with the manager's existing signing key. Keep that key so "
        "updates keep working.",
    ])


def github_notes(root: Path, version: str, checked: str | None, draft: bool = False) -> tuple[str, str]:
    text = read(root / "CHANGELOG.md")
    match = re.search(rf"^## {re.escape(version)} \(\d{{4}}-\d{{2}}-\d{{2}}\)$", text, re.MULTILINE)
    if not match:
        fail(f"CHANGELOG has no dated {version} heading. Run cut first.")
    bounds = section_bounds(text, match.group(0)[3:])
    loose, grouped = parse_section(text[bounds[0]:bounds[1]], version)
    if len(loose) != 1 or not loose[0].startswith("* **Facebook:** "):
        fail(f"the {version} section opens with {len(loose)} bullets before its first ### heading, and the notes "
             "open with one, the Facebook summary cut writes")
    intro = plain(BULLET.match(loose[0]).group("text"))
    blocks = {name: [] for name in list(GROUPS.values()) + [TOOLING_GROUP]}
    for heading, scope, bullet in grouped:
        if scope == "Tooling":
            blocks[TOOLING_GROUP].append(plain(bullet))
        elif heading in GROUPS:
            blocks[GROUPS[heading]].append(plain(bullet))
        else:
            fail(f"the {version} section has a ### {heading} heading the notes have no place for "
                 f"({', '.join(GROUPS)})")
    parts = [intro]
    for name, bullets in blocks.items():
        if bullets:
            parts.append(name)
            parts.extend("- " + bullet for bullet in bullets)
    parts.append(f"The [changelog](https://github.com/{REPOSITORY}/blob/main/CHANGELOG.md) has every earlier release too.")
    parts.append("How to update")
    parts.append(update_steps(root))
    parts.append(f"The `.mpp` download is a patch bundle, not a Facebook APK. "
                 f"[Installation instructions](https://github.com/{REPOSITORY}#install).")
    parts.append("Look over a diagnostic report before you share it. The filter can still miss private text it "
                 "doesn't recognize.")
    if checked:
        parts.append("What was checked")
        parts.append(checked)
    elif not draft:
        fail("--checked is the What was checked paragraph, and only a --draft goes without it")
    notes = "\n\n".join(parts) + "\n"
    if any(d in notes for d in DASHES):
        fail("the notes have an em or en dash")
    kept = sum(1 for line in notes.split("\n") if line.startswith("- "))
    if kept != len(grouped):
        fail(f"{kept} of the section's {len(grouped)} bullets made it into the notes")
    _, targets = catalog_targets(root)
    newest = targets[0][0]
    said, sentence = target_in(notes)
    if said is None and not draft:
        fail(f"nothing in the notes says which Facebook build this release targets, and the app's update check "
             f"reads it from them. Say \"All N patches applied ... to Facebook {newest}\" in the checked paragraph, "
             f"with \"Facebook\" right after \"to\" (\"to both Facebook {newest} builds\" isn't read: installed "
             f"builds parse the same phrase, so the checker can't loosen it).")
    if said is not None and said != newest:
        fail(f"the app's update check would read Facebook {said} as this release's target, from \"{sentence}\". "
             f"It's {newest}: reword that sentence, or say {newest} first.")
    summary = (f"notes for {version}: the summary and {kept} bullets, "
               + (f"target Facebook {said}" if said else "no target sentence yet (draft)"))
    return notes, summary


def facts_in(text: str) -> tuple[set, set]:
    counts = set(re.findall(r"(?<![\d.])(\d+) patches\b", text))
    builds = set(re.findall(r"Facebook\s+(\d+(?:\.\d+)+)(?!\d)", text))
    return counts, builds


def swap(text: str, pattern: str, replacement: str, name: str) -> str:
    new, count = re.subn(pattern, lambda _: replacement, text, flags=re.MULTILINE)
    if count != 1:
        fail(f"{name}: {count} places matched {pattern[:60]!r}, not one")
    return new


def index(root: Path, version: str, created: str, bundle_summary: str, readme_summary: str,
          runtime: int, patch: int) -> str:
    if not VERSION.match(version):
        fail(f"{version} isn't X.Y.Z")
    try:
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", created):
            raise ValueError(created)
        datetime.datetime.fromisoformat(created)
    except ValueError:
        fail(f"{created} isn't a UTC time written YYYY-MM-DDTHH:MM:SS, with no Z or offset, as Morphe Manager reads it")
    if runtime <= 0 or patch <= 0:
        fail("the runtime and patch test counts come from a full run, so neither is zero")
    count, targets = catalog_targets(root)
    newest, codes = targets[0]
    older = [v for v, _ in targets[1:]]
    floor = manager_floor(root)
    bundle_path = root / "patches-bundle.json"
    bundle = json.loads(read(bundle_path))
    previous = str(bundle.get("version", ""))
    if previous == version:
        fail(f"patches-bundle.json already names {version}")

    first = f"Hushfacebook v{version} targets Facebook {newest} ({PACKAGE}), the newest release"
    first += f", and {spoken(older)} still work." if older else "."
    patch_line = f"Patch the arm64-v8a bundle of Facebook {newest}"
    if "ARM64_V8A" in codes:
        patch_line += f", build {codes['ARM64_V8A']}"
    if "ARMEABI_V7A" in codes:
        patch_line += f", or its 32-bit build {codes['ARMEABI_V7A']}"
    patch_line += "."
    if older:
        older_builds = [f"{v} ({next(iter(c.values()))})" if c else v for v, c in targets[1:]]
        patch_line += f" Build{'s' if len(older) > 1 else ''} {spoken(older_builds)} still work{'' if len(older) > 1 else 's'}."
    description = "\n\n".join([
        first,
        bundle_summary,
        f"Validation: {runtime} runtime tests passed locally. All {patch} patch tests passed too, along with both "
        f"Android lints. All {count} patches applied to Facebook {spoken([newest] + older)}, and the resource and "
        "injected-code checks passed.",
        "Review diagnostic reports for private data before sharing them.",
        f"Needs Morphe Manager {floor} or newer. {patch_line} The .mpp is a patch bundle, not a Facebook APK. Keep "
        "the manager's existing signing key when updating.",
    ])
    if any(d in description + readme_summary for d in DASHES):
        fail("the summaries have an em or en dash")
    counts, builds = facts_in(description)
    if counts != {str(count)} or builds != {newest}:
        fail(f"the description has to name {count} patches and Facebook {newest} alone, and it names "
             f"{', '.join(sorted(counts)) or 'no'} patch counts and Facebook {', '.join(sorted(builds))}. "
             "Check the bundle summary.")
    bundle.update({
        "created_at": created,
        "description": description,
        "download_url": f"https://github.com/{REPOSITORY}/releases/download/v{version}/patches-{version}.mpp",
        "version": version,
    })
    readme_path = root / "README.md"
    sentence = (f"The latest release is [v{version}](https://github.com/{REPOSITORY}/releases/tag/v{version}), with "
                f"{count} patches. It includes all changes since v{previous}, among them {readme_summary.rstrip('.')}, "
                "as the [changelog](CHANGELOG.md) describes.")
    readme = swap(read(readme_path), r"^The latest release is \[v\d+\.\d+\.\d+\]\(.*$", sentence, "README.md")
    form_path = root / ".github" / "ISSUE_TEMPLATE" / "bug_report.yml"
    form, swapped = re.subn(r"(?m)^([ \t]*placeholder:[ \t]*)Version \S+ for Facebook \S+[ \t]*$",
                            lambda m: f"{m.group(1)}Version {version} for Facebook {newest}", read(form_path))
    if swapped != 1:
        fail(f"bug_report.yml: {swapped} version placeholders matched, not one")
    write(bundle_path, json.dumps(bundle, indent=2, ensure_ascii=False) + "\n")
    write(readme_path, readme)
    write(form_path, form)
    return f"index, README and bug form point at v{version}: {count} patches, Facebook {newest}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(ROOT), help=argparse.SUPPRESS)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("cut")
    p.add_argument("--version", required=True)
    p.add_argument("--summary", required=True)
    p.add_argument("--date")
    sub.add_parser("bump").add_argument("--version", required=True)
    sub.add_parser("check-translations")
    p = sub.add_parser("github-notes")
    p.add_argument("--version", required=True)
    p.add_argument("--checked")
    p.add_argument("--draft", action="store_true")
    p.add_argument("--out", required=True)
    p = sub.add_parser("index")
    for name in ("--version", "--created", "--bundle-summary", "--readme-summary"):
        p.add_argument(name, required=True)
    p.add_argument("--runtime", type=int, required=True)
    p.add_argument("--patch", type=int, required=True)
    args = parser.parse_args()
    root = Path(args.root)
    if args.command == "cut":
        said = cut(root, args.version, read_paragraph(args.summary, "The summary"), args.date)
    elif args.command == "bump":
        said = bump(root, args.version)
    elif args.command == "check-translations":
        said = check_translations(root)
    elif args.command == "github-notes":
        checked = read_paragraph(args.checked, "The checked paragraph") if args.checked else None
        notes, said = github_notes(root, args.version, checked, args.draft)
        write(Path(args.out), notes)
    else:
        said = index(root, args.version, args.created, read_paragraph(args.bundle_summary, "The bundle summary"),
                     read_paragraph(args.readme_summary, "The README summary"), args.runtime, args.patch)
    print(f"[release] {said}")


if __name__ == "__main__":
    main()
