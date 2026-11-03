"""Turn the translation tables into the Java class the extension carries.

Ported from SysAdminDoc/hushfeed scripts/gen-l10n.py at 1f1f81a29ffbe8468a41067b22c05a50283ef9c4
(GPL-3.0). Modified for Hushfacebook: tab tables only, the shared library's paths, ASCII-only
output, and a refusal of invisible characters.

Reads every extensions/shared/library/src/main/l10n/<lang>.tsv (English TAB translation, one
entry per line, # comments) and writes, in extensions/shared/library/src/main/java/app/morphe/
extension/shared:

  L10nTranslations.java   the language tags and which table answers each
  L10nTable<Lang>.java    one class per language holding its table

and deletes any L10nTable<Lang>.java left from a table that's gone.

The English text in the code is the lookup key, so only the translations are stored. They
travel in the extension's own DEX: Hushfacebook adds no resources to Facebook.

Each language is its own class because all of them in one file grew past the 3 MB lint will
read. Its table is filled across several methods so no single one approaches the 64 KB bytecode
limit. Every character past ASCII is written as a \\u escape,
so the generated file reads the same in any editor and can't hide a character in plain sight.

Anything it can't make sense of stops the run with the file and line rather than being dropped
on the way past: a row with no source text, a key that begins with the comment character, a
duplicate, a control or invisible formatting character, and a translation whose placeholders
aren't the ones its key carries. L10nCatalogTest makes the same checks of the generated class,
so a table edited without running this fails the build.

Context. A table may carry a comment line above a row, "# context: what the row is and where it
shows", and the reader skips it. A key is a whole sentence or phrase, never a lone word joined
into one, so a translator sees the sentence the words sit in.

Plurals. A count is written as two English keys, the one form and the other form, and
L10n.quantity picks between them by the phone language's CLDR plural rule. A language with more
forms keeps each extra one as a row keyed by the other form plus "|" and the CLDR category name.

Run from the repository root: python scripts/gen-l10n.py
"""
import io
import os
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
L10N = os.path.join(ROOT, "extensions", "shared", "library", "src", "main", "l10n")
JAVA = os.path.join(ROOT, "extensions", "shared", "library", "src", "main", "java", "app", "morphe",
                    "extension", "shared", "L10nTranslations.java")

# Entries per generated method. 60 pairs is roughly 2 KB of bytecode, well inside the limit.
CHUNK = 60

# A generated table class's file name, which a run deletes when its table is gone.
TABLE_FILE = re.compile(r"^L10nTable[A-Z]\w*\.java$")

# What every generated file starts with.
HEADER = [
    "/*",
    " * Copyright 2026 Hushfacebook contributors",
    " * https://github.com/SysAdminDoc/Hushfacebook",
    " */",
    "package app.morphe.extension.shared;",
    "",
]

# Three languages have two ISO codes, and the two runtimes disagree about which one to give
# back. Android's Locale.getLanguage() answers with the legacy code, a JVM since 17 answers with
# the new one, so the same table has to be reachable under both.
ALIASES = {"in": "id", "id": "in", "iw": "he", "he": "iw", "ji": "yi", "yi": "ji"}

# Both spellings a format string can use, numbered and bare.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")

COMMENT = "#"

# A plural form English doesn't have: the other form's key, a bar and the CLDR category.
# DOTALL, since a key can hold line breaks: ru's "Set when you patched, ..." forms went unmatched.
PLURAL_VARIANT = re.compile(r"^(?P<base>.+)\|(?P<category>zero|one|two|few|many)$", re.DOTALL)


def plural_base(english):
    """The other form a |category row belongs to, or the key itself for an ordinary row."""
    match = PLURAL_VARIANT.match(english)
    return match.group("base") if match else english


# Characters that draw nothing without being control or format characters: Unicode's
# Default_Ignorable fillers, joiners and selectors, and the blank braille cell.
INVISIBLE_RANGES = [
    (0x034F, 0x034F), (0x115F, 0x1160), (0x17B4, 0x17B5), (0x180B, 0x180F), (0x2800, 0x2800),
    (0x3164, 0x3164), (0xFE00, 0xFE0F), (0xFFA0, 0xFFA0), (0xFFF0, 0xFFF8), (0x1BCA0, 0x1BCA3),
    (0x1D173, 0x1D17A), (0xE0000, 0xE0FFF),
]


def invisible(text):
    """The first control, formatting or other invisible character in text, or None. Newlines
    are \\n by now."""
    for char in text:
        if char == "\n":
            continue
        if unicodedata.category(char) in ("Cc", "Cf", "Co", "Cn", "Zl", "Zp"):
            return char
        if any(low <= ord(char) <= high for low, high in INVISIBLE_RANGES):
            return char
    return None


def add(entries, english, translated, path, number):
    english = english.replace("\\n", "\n")
    translated = translated.replace("\\n", "\n")
    if not english.strip():
        sys.exit("%s:%d: no source text" % (path, number))
    if not translated.strip():
        sys.exit("%s:%d: no translation for: %s" % (path, number, english))
    if english.startswith(COMMENT):
        sys.exit("%s:%d: a key can't begin with %s: it can't be told from a comment"
                 % (path, number, COMMENT))
    if english in entries:
        sys.exit("%s:%d: duplicate entry: %s" % (path, number, english))
    for text in (english, translated):
        char = invisible(text)
        if char is not None:
            sys.exit("%s:%d: U+%04X is a control or invisible character: %s"
                     % (path, number, ord(char), english))
    # A dropped, added, renumbered or retyped placeholder, in one comparison. The runtime hands
    # the same arguments to whichever table is loaded, so a translation that asks for different
    # ones formats the wrong value or throws on the phone. Numbered placeholders may move;
    # unnumbered ones keep their order, because String.format fills them positionally.
    wanted = PLACEHOLDER.findall(plural_base(english))
    given = PLACEHOLDER.findall(translated)
    all_numbered = all("$" in p for p in wanted) if wanted else False
    if all_numbered:
        if sorted(wanted) != sorted(given):
            sys.exit("%s:%d: numbered placeholders %s became %s in: %s"
                     % (path, number, wanted, given, english.replace("\n", "\\n")))
    elif wanted != given:
        sys.exit("%s:%d: placeholders %s became %s in: %s"
                 % (path, number, wanted, given, english.replace("\n", "\\n")))
    entries[english] = translated


def read(path):
    entries = {}
    # utf-8-sig, not utf-8: Excel writes a byte order mark on any round trip, and it would
    # otherwise become part of the first key.
    with io.open(path, encoding="utf-8-sig") as handle:
        for number, line in enumerate(handle, 1):
            line = line.rstrip("\r\n")
            if not line:
                continue
            # A comment has no tab. One that does is an entry whose key starts with the comment
            # character, and add() refuses it by name rather than letting the line disappear.
            if line.startswith(COMMENT) and "\t" not in line:
                continue
            if "\t" not in line:
                sys.exit("%s:%d: no tab" % (path, number))
            english, translated = line.split("\t", 1)
            if "\t" in translated:
                sys.exit("%s:%d: more than one tab" % (path, number))
            add(entries, english, translated, path, number)
    # A |category row is an extra form of a row that has to be there too. L10n reads the other
    # form's row for every other count, and the translation check counts the variant as that row,
    # so without it the text would quietly stay English.
    for english in entries:
        base = plural_base(english)
        if base != english and base not in entries:
            sys.exit("%s: %s has no row for its other form: %s"
                     % (path, english.replace("\n", "\\n"), base.replace("\n", "\\n")))
    return entries


def literal(text):
    out = []
    for char in text:
        code = ord(char)
        if char == "\\":
            out.append("\\\\")
        elif char == '"':
            out.append('\\"')
        elif char == "\n":
            out.append("\\n")
        elif code < 0x80:
            out.append(char)
        elif code <= 0xFFFF:
            out.append("\\u%04x" % code)
        else:
            # Past the Basic Multilingual Plane a Java char is half of a surrogate pair.
            code -= 0x10000
            out.append("\\u%04x\\u%04x" % (0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF)))
    return '"%s"' % "".join(out)


def table_class(lang):
    """The class holding a table. Only the first letter is raised, so pt-rBR stays apart."""
    name = lang.replace("-", "_").replace("+", "_")
    return "L10nTable" + name[:1].upper() + name[1:]


def main():
    tables = {}
    for name in sorted(os.listdir(L10N)):
        if not name.endswith(".tsv"):
            continue
        tables[name[:-4]] = read(os.path.join(L10N, name))
    if not tables:
        sys.exit("no .tsv tables in " + L10N)

    lines = HEADER + [
        "import java.util.Map;",
        "",
        "/**",
        " * Hushfacebook's text in every language the bundle carries, one L10nTable class per language.",
        " *",
        " * <p>Generated by scripts/gen-l10n.py from extensions/shared/library/src/main/l10n. Don't",
        " * edit it by hand: edit the table and run the script.",
        " */",
        "public final class L10nTranslations {",
        "    private L10nTranslations() {",
        "    }",
        "",
    ]

    languages = sorted(tables)
    lines.append("    /** The language tags with a table, lower case. */")
    lines.append("    static final String[] LANGUAGES = {%s};"
                 % ", ".join(literal(lang.lower()) for lang in languages))
    lines.append("")
    lines.append("    /**")
    lines.append("     * The table for one language tag, or null when nothing was translated into it.")
    lines.append("     * A language with two ISO codes answers to both, because Android reports the")
    lines.append("     * legacy one and a desktop JVM reports the new one.")
    lines.append("     */")
    lines.append("    static Map<String, String> of(String language) {")
    lines.append("        switch (language) {")
    known = [other.lower() for other in languages]
    for lang in languages:
        name = lang.lower()
        labels = [name]
        head, dash, tail = name.partition("-")
        alias = ALIASES.get(head)
        if alias and alias + dash + tail not in known:
            labels.append(alias + dash + tail)
        for label in labels:
            lines.append("            case %s:" % literal(label))
        lines.append("                return %s.build();" % table_class(lang))
    lines.append("            default:")
    lines.append("                return null;")
    lines.append("        }")
    lines.append("    }")
    lines.append("}")

    folder = os.path.dirname(JAVA)
    written = {JAVA: lines}
    total = 0
    for lang in languages:
        entries = tables[lang]
        total += len(entries)
        pairs = [(english, entries[english]) for english in sorted(entries)]
        chunks = [pairs[at:at + CHUNK] for at in range(0, len(pairs), CHUNK)]
        name = table_class(lang)

        table = HEADER + [
            "import java.util.HashMap;",
            "import java.util.Map;",
            "",
            "/**",
            " * Hushfacebook's text for the %s table, which {@link L10nTranslations} hands out." % lang,
            " *",
            " * <p>Generated by scripts/gen-l10n.py from extensions/shared/library/src/main/l10n/%s.tsv." % lang,
            " * Don't edit it by hand: edit the table and run the script.",
            " */",
            "final class %s {" % name,
            "    private %s() {" % name,
            "    }",
            "",
            "    static Map<String, String> build() {",
            "        Map<String, String> table = new HashMap<>(%d);" % (len(pairs) * 2),
        ]
        for index in range(len(chunks)):
            table.append("        fill%d(table);" % index)
        table.append("        return table;")
        table.append("    }")
        for index, chunk in enumerate(chunks):
            table.append("")
            table.append("    private static void fill%d(Map<String, String> table) {" % index)
            for english, translated in chunk:
                table.append("        table.put(%s," % literal(english))
                table.append("                %s);" % literal(translated))
            table.append("    }")
        table.append("}")
        written[os.path.join(folder, name + ".java")] = table

    for path, body in written.items():
        with io.open(path, "w", encoding="ascii", newline="\n") as handle:
            handle.write("\n".join(body) + "\n")
    for stale in sorted(os.listdir(folder)):
        if TABLE_FILE.match(stale) and os.path.join(folder, stale) not in written:
            os.remove(os.path.join(folder, stale))
            print("deleted %s, its table is gone" % stale)
    print("wrote %d translations for %s" % (total, ", ".join(languages)))


if __name__ == "__main__":
    main()
