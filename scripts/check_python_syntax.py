import ast
import sys
from pathlib import Path

fails = []
for root in ["findoc-verify", "SubbyPythonLoan", "FraudPython"]:
    for p in Path(root).rglob("*.py"):
        sp = str(p).replace("\\", "/")
        if any(s in sp for s in ["/__pycache__/", "/.venv/", "/legacy/", "/venv/"]):
            continue
        try:
            ast.parse(p.read_text(encoding="utf-8"))
        except SyntaxError as e:
            fails.append((str(p), str(e)))

print(f"fails: {len(fails)}")
for f, e in fails[:20]:
    print(" ", f, "->", e)
sys.exit(1 if fails else 0)
