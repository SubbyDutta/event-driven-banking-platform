#!/usr/bin/env python3
"""
Strip inline comments from Java, Python and JS/TS files while preserving
Javadoc / JSDoc, module/class/function docstrings, and tooling directives.

Java:
  - Remove `//` line and trailing comments
  - Remove plain `/* ... */` block comments
  - Preserve `/** ... */` Javadoc (class/method docs)

Python:
  - Remove `#` line and trailing comments
  - Preserve shebang `#!`, encoding lines, and pragma directives
    (`# noqa`, `# type:`, `# pragma:`, `# pylint:`, `# fmt:`, `# isort:`)
  - Preserve docstrings (triple-quoted strings used as the first statement
    of a module / class / function).

JS / TS / JSX / TSX:
  - Remove `//` and plain `/* ... */` comments
  - Preserve `/** ... */` JSDoc
  - Preserve directive comments: eslint-*, @ts-*, prettier-ignore,
    biome-ignore, istanbul, c8, jshint, jslint, globals, sourceMappingURL,
    sourceURL.
  - Preserve string literals (single, double, template with `${...}`)
  - Preserve regex literals (heuristic: `/` after a position where a regex
    can start).
  - Strip `{/* ... */}` JSX comment idiom (the tight form, no whitespace)
    entirely so the empty `{}` doesn't end up as a JSX syntax error.

Blank-line policy: at most two consecutive blank lines (PEP8-friendly for
Python; harmless for Java / JS).
"""

from __future__ import annotations
import re
import sys
from pathlib import Path

PYTHON_PRESERVE = re.compile(r"#\s*(noqa|type:|pragma:|pylint:|fmt:|isort:|coding[:=])")

def strip_python_file(text: str) -> str:
    out_lines = []
    in_triple_single = False
    in_triple_double = False
    for i, line in enumerate(text.split("\n")):
        if i == 0 and line.startswith("#!"):
            out_lines.append(line)
            continue
        line_started_in_string = in_triple_single or in_triple_double
        scan = line
        new_chars = []
        in_string_char = None
        i2 = 0
        while i2 < len(scan):
            c = scan[i2]
            two = scan[i2:i2+3]
            if not in_triple_single and not in_triple_double and in_string_char is None:
                if two == '"""':
                    in_triple_double = True
                    new_chars.append(two); i2 += 3; continue
                if two == "'''":
                    in_triple_single = True
                    new_chars.append(two); i2 += 3; continue
                if c == '"' or c == "'":
                    in_string_char = c
                    new_chars.append(c); i2 += 1; continue
                if c == "#":
                    rest = scan[i2:]
                    if PYTHON_PRESERVE.match(rest):
                        new_chars.append(rest)
                        i2 = len(scan)
                        continue
                    i2 = len(scan)
                    continue
                new_chars.append(c); i2 += 1
            elif in_triple_double:
                if two == '"""':
                    in_triple_double = False
                    new_chars.append(two); i2 += 3; continue
                new_chars.append(c); i2 += 1
            elif in_triple_single:
                if two == "'''":
                    in_triple_single = False
                    new_chars.append(two); i2 += 3; continue
                new_chars.append(c); i2 += 1
            elif in_string_char is not None:
                if c == "\\" and i2 + 1 < len(scan):
                    new_chars.append(scan[i2:i2+2]); i2 += 2; continue
                if c == in_string_char:
                    in_string_char = None
                new_chars.append(c); i2 += 1
            else:
                new_chars.append(c); i2 += 1
        new_line = "".join(new_chars).rstrip() if not (line_started_in_string or in_triple_single or in_triple_double) else "".join(new_chars)
        original_stripped = line.strip()
        if (not line_started_in_string
                and original_stripped.startswith("#")
                and not PYTHON_PRESERVE.match(original_stripped)
                and not (i == 0 and line.startswith("#!"))):
            continue
        out_lines.append(new_line if new_line or not line else line if line.strip() else "")
    cleaned = []
    blank = 0
    for l in out_lines:
        if l.strip() == "":
            blank += 1
            if blank <= 2:
                cleaned.append("")
        else:
            blank = 0
            cleaned.append(l)
    return "\n".join(cleaned)


def strip_java_file(text: str) -> str:
    """
    Two-pass strip:
      1) Tokenize, preserving string/char literals and Javadoc /** ... */.
      2) Remove all `//` and non-Javadoc `/* ... */` comments.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        two = text[i:i+2]
        three = text[i:i+3]
        if three == "/**":
            end = text.find("*/", i + 3)
            if end == -1:
                out.append(text[i:])
                break
            out.append(text[i:end+2])
            i = end + 2
            continue
        if two == "/*":
            end = text.find("*/", i + 2)
            if end == -1:
                i = n
                break
            i = end + 2
            continue
        if two == "//":
            nl = text.find("\n", i)
            if nl == -1:
                i = n
                break
            i = nl
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        out.append(c)
        i += 1
    cleaned = "".join(out)
    lines = [ln.rstrip() for ln in cleaned.split("\n")]
    final = []
    blank = 0
    for ln in lines:
        if ln.strip() == "":
            blank += 1
            if blank <= 2:
                final.append("")
        else:
            blank = 0
            final.append(ln)
    return "\n".join(final)


JS_PRESERVE_LINE = re.compile(
    r"^//\s*("
    r"eslint-|"
    r"@ts-|"
    r"prettier-ignore|"
    r"biome-ignore|"
    r"istanbul\b|"
    r"c8\b|"
    r"jshint\b|"
    r"jslint\b|"
    r"globals?\b|"
    r"#\s*sourceMappingURL=|"
    r"#\s*sourceURL=|"
    r"@\s*sourceMappingURL="
    r")"
)
JS_PRESERVE_BLOCK = re.compile(
    r"^/\*\s*("
    r"eslint-|"
    r"@ts-|"
    r"prettier-ignore|"
    r"biome-ignore|"
    r"istanbul\b|"
    r"c8\b|"
    r"jshint\b|"
    r"jslint\b|"
    r"globals?\b"
    r")"
)

JS_REGEX_PRECEDING_KEYWORDS = {
    "return", "typeof", "instanceof", "in", "of", "new", "throw", "yield",
    "void", "delete", "await", "case", "do", "else",
}


def _js_can_regex_start(prev_token: str) -> bool:
    if prev_token == "":
        return True
    if prev_token in JS_REGEX_PRECEDING_KEYWORDS:
        return True
    if len(prev_token) == 1 and prev_token in "+-*/%&|^~<>!=?:;,({[":
        return True
    if prev_token in ("=>", "==", "===", "!=", "!==", "<=", ">=", "&&", "||",
                      "??", "<<", ">>", ">>>", "**", "+=", "-=", "*=", "/=",
                      "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "**=",
                      "&&=", "||=", "??=", "..."):
        return True
    return False


_JSX_COMMENT_RE = re.compile(r"\{/\*[\s\S]*?\*/\}")


def strip_js_file(text: str) -> str:
    """
    State-machine strip for JS / TS / JSX / TSX. Tracks strings, template
    literals (with `${...}` interpolation), regex literals, and comments.
    Preserves JSDoc and directive comments.
    """
    text = _JSX_COMMENT_RE.sub("", text)
    out = []
    i = 0
    n = len(text)
    prev_significant = ""
    template_stack = []

    def is_ident_char(ch: str) -> bool:
        return ch.isalnum() or ch == "_" or ch == "$"

    while i < n:
        c = text[i]
        two = text[i:i+2]
        three = text[i:i+3]

        if template_stack and template_stack[-1]["expr_depth"] == 0:
            t = template_stack[-1]
            if c == "`":
                out.append(c)
                i += 1
                template_stack.pop()
                prev_significant = "`literal"
                continue
            if two == "${":
                out.append(two)
                i += 2
                t["expr_depth"] = 1
                prev_significant = "{"
                continue
            if c == "\\" and i + 1 < n:
                out.append(text[i:i+2])
                i += 2
                continue
            out.append(c)
            i += 1
            continue

        if three == "/**" and (i + 3 >= n or text[i+3] != "/"):
            end = text.find("*/", i + 3)
            if end == -1:
                out.append(text[i:])
                break
            out.append(text[i:end+2])
            i = end + 2
            prev_significant = "jsdoc"
            continue

        if two == "//":
            nl = text.find("\n", i)
            line = text[i:nl] if nl != -1 else text[i:]
            if JS_PRESERVE_LINE.match(line):
                out.append(line)
                i = nl if nl != -1 else n
                continue
            i = nl if nl != -1 else n
            continue

        if two == "/*":
            end = text.find("*/", i + 2)
            block = text[i:end+2] if end != -1 else text[i:]
            if JS_PRESERVE_BLOCK.match(block):
                out.append(block)
                i = end + 2 if end != -1 else n
                continue
            if end == -1:
                i = n
                break
            i = end + 2
            continue

        if c == '"' or c == "'":
            quote = c
            j = i + 1
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                if text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            prev_significant = "literal"
            continue

        if c == "`":
            out.append(c)
            i += 1
            template_stack.append({"expr_depth": 0})
            prev_significant = "`literal"
            continue

        if c == "/" and _js_can_regex_start(prev_significant):
            j = i + 1
            in_class = False
            ok = False
            while j < n:
                ch = text[j]
                if ch == "\\" and j + 1 < n:
                    j += 2
                    continue
                if ch == "\n":
                    break
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    j += 1
                    while j < n and text[j].isalpha():
                        j += 1
                    ok = True
                    break
                j += 1
            if ok:
                out.append(text[i:j])
                i = j
                prev_significant = "literal"
                continue

        if c.isspace():
            out.append(c)
            i += 1
            continue

        if is_ident_char(c):
            j = i
            while j < n and is_ident_char(text[j]):
                j += 1
            tok = text[i:j]
            out.append(tok)
            i = j
            prev_significant = tok
            continue

        if template_stack and template_stack[-1]["expr_depth"] > 0:
            if c == "{":
                template_stack[-1]["expr_depth"] += 1
            elif c == "}":
                template_stack[-1]["expr_depth"] -= 1
                if template_stack[-1]["expr_depth"] == 0:
                    out.append(c)
                    i += 1
                    prev_significant = "}"
                    continue

        if c in "=<>!+-*/%&|^~?:":
            j = i
            while j < n and text[j] in "=<>!+-*/%&|^~?:":
                j += 1
            tok = text[i:j]
            out.append(tok)
            i = j
            prev_significant = tok
            continue

        out.append(c)
        i += 1
        if c.strip():
            prev_significant = c

    cleaned = "".join(out)
    lines = [ln.rstrip() for ln in cleaned.split("\n")]
    final = []
    blank = 0
    for ln in lines:
        if ln.strip() == "":
            blank += 1
            if blank <= 2:
                final.append("")
        else:
            blank = 0
            final.append(ln)
    return "\n".join(final)


def process(path: Path, language: str, dry_run: bool=False) -> bool:
    text = path.read_text(encoding="utf-8")
    if language == "java":
        new = strip_java_file(text)
    elif language == "js":
        new = strip_js_file(text)
    else:
        new = strip_python_file(text)
    if new != text:
        if not dry_run:
            path.write_text(new, encoding="utf-8")
        return True
    return False


_JS_PATTERNS = ("*.js", "*.jsx", "*.ts", "*.tsx", "*.mjs", "*.cjs")


def main():
    if len(sys.argv) < 3:
        print("usage: strip_comments.py <java|python|js> <root> [--dry]", file=sys.stderr)
        sys.exit(2)
    lang = sys.argv[1]
    root = Path(sys.argv[2])
    dry = "--dry" in sys.argv[3:]
    if lang == "java":
        patterns = ("*.java",)
    elif lang == "js":
        patterns = _JS_PATTERNS
    else:
        patterns = ("*.py",)
    changed = 0
    total = 0
    for pattern in patterns:
        for p in root.rglob(pattern):
            sp = str(p).replace("\\", "/")
            if any(seg in sp for seg in (
                "/target/", "/__pycache__/", "/node_modules/",
                "/legacy/", "/.venv/", "/venv/", "/build/", "/dist/",
                "/.next/", "/coverage/"
            )):
                continue
            total += 1
            if process(p, lang, dry):
                changed += 1
    print(f"{lang}: {changed}/{total} files changed under {root}")


if __name__ == "__main__":
    main()
