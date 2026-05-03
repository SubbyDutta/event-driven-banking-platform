#!/usr/bin/env python3
"""
Strip inline comments from Java and Python files while preserving Javadoc,
module/class/function docstrings, and linter directives.

Java:
  - Remove `//` line and trailing comments
  - Remove `/* ... */` block comments INSIDE method bodies (heuristic: any
    block comment that is NOT preceded by only whitespace at column 0 OR is
    not a `/** ... */` Javadoc)
  - Preserve `/** ... */` Javadoc (class/method docs)

Python:
  - Remove `#` line and trailing comments
  - Preserve shebang `#!`, encoding lines, and pragma directives
    (`# noqa`, `# type:`, `# pragma:`, `# pylint:`, `# fmt:`, `# isort:`)
  - Preserve docstrings (triple-quoted strings used as the first statement
    of a module / class / function).
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
    # Collapse multiple consecutive blank lines to a single blank line
    cleaned = []
    blank = 0
    for l in out_lines:
        if l.strip() == "":
            blank += 1
            if blank <= 1:
                cleaned.append("")
        else:
            blank = 0
            cleaned.append(l)
    return "\n".join(cleaned)


# ---- Java ----

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
        # Javadoc /** ... */ — preserve verbatim
        if three == "/**":
            end = text.find("*/", i + 3)
            if end == -1:
                out.append(text[i:])
                break
            out.append(text[i:end+2])
            i = end + 2
            continue
        # Block comment /* ... */ — strip
        if two == "/*":
            end = text.find("*/", i + 2)
            if end == -1:
                i = n
                break
            i = end + 2
            continue
        # Line comment // ... \n — strip (keep newline)
        if two == "//":
            nl = text.find("\n", i)
            if nl == -1:
                i = n
                break
            i = nl
            continue
        # String literal "..."
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
        # Char literal '...'
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
    # Trim trailing whitespace per line and collapse runs of blank lines
    lines = [ln.rstrip() for ln in cleaned.split("\n")]
    final = []
    blank = 0
    for ln in lines:
        if ln.strip() == "":
            blank += 1
            if blank <= 1:
                final.append("")
        else:
            blank = 0
            final.append(ln)
    return "\n".join(final)


# ---- driver ----

def process(path: Path, language: str, dry_run: bool=False) -> bool:
    text = path.read_text(encoding="utf-8")
    if language == "java":
        new = strip_java_file(text)
    else:
        new = strip_python_file(text)
    if new != text:
        if not dry_run:
            path.write_text(new, encoding="utf-8")
        return True
    return False


def main():
    if len(sys.argv) < 3:
        print("usage: strip_comments.py <java|python> <root> [--dry]", file=sys.stderr)
        sys.exit(2)
    lang = sys.argv[1]
    root = Path(sys.argv[2])
    dry = "--dry" in sys.argv[3:]
    pattern = "*.java" if lang == "java" else "*.py"
    changed = 0
    total = 0
    for p in root.rglob(pattern):
        if "/target/" in str(p).replace("\\", "/"):
            continue
        if "/__pycache__/" in str(p).replace("\\", "/"):
            continue
        if "/node_modules/" in str(p).replace("\\", "/"):
            continue
        if "/legacy/" in str(p).replace("\\", "/"):
            continue
        if "/.venv/" in str(p).replace("\\", "/"):
            continue
        if "/venv/" in str(p).replace("\\", "/"):
            continue
        total += 1
        if process(p, lang, dry):
            changed += 1
    print(f"{lang}: {changed}/{total} files changed under {root}")


if __name__ == "__main__":
    main()
