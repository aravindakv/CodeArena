#!/usr/bin/env python3
"""
Write the code files from CodeArena guide chapters into your repository.

Usage (run from your repo root, e.g. ~/Projects/codearena):
    python3 extract_code.py path/to/01-prerequisites-and-repo.md            # dry run: lists what it would write
    python3 extract_code.py path/to/01-*.md --write                          # writes the files
    python3 extract_code.py path/to/codearena-guide/*.md --write             # every chapter
    python3 extract_code.py path/to/06-*.md --write --force                  # also overwrite existing files

Rules:
  * Only code blocks introduced by a line of the form   **File:** `relative/path`   are extracted.
    Every other code block in the guide is a command or an example and is ignored.
  * Paths are relative to the current directory (or --root); absolute paths and '..' are refused.
  * Existing files are never overwritten unless you pass --force; identical files are reported as unchanged.
  * Files that start with '#!' are made executable.
"""
import argparse
import re
import stat
import sys
from pathlib import Path

CAPTION = re.compile(r"^\*\*File:\*\* `([^`]+)`\s*$")
FENCE_OPEN = re.compile(r"^(`{3,})[\w+.-]*\s*$")


def parse(text: str, source: str):
    """Yield (path, content) for every captioned code block."""
    lines = text.splitlines(keepends=True)
    i = 0
    while i < len(lines):
        m = CAPTION.match(lines[i].rstrip("\n"))
        if not m:
            i += 1
            continue
        path = m.group(1)
        i += 1
        while i < len(lines) and not lines[i].strip():
            i += 1
        opening = FENCE_OPEN.match(lines[i].rstrip("\n")) if i < len(lines) else None
        if not opening:
            sys.exit(f"{source}: '**File:** `{path}`' is not followed by a code block (line {i + 1})")
        fence = opening.group(1)
        i += 1
        body = []
        while i < len(lines) and lines[i].rstrip("\n") != fence:
            body.append(lines[i])
            i += 1
        if i >= len(lines):
            sys.exit(f"{source}: unterminated code block for {path}")
        i += 1
        yield path, "".join(body)


def safe(root: Path, rel: str) -> Path:
    p = Path(rel)
    if p.is_absolute() or ".." in p.parts:
        sys.exit(f"refusing unsafe path: {rel}")
    return root / p


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("chapters", nargs="+", help="chapter .md files")
    ap.add_argument("--write", action="store_true", help="write files (default: dry run)")
    ap.add_argument("--force", action="store_true", help="overwrite files that differ")
    ap.add_argument("--root", default=".", help="repository root (default: current directory)")
    args = ap.parse_args()
    root = Path(args.root).resolve()

    counts = {"write": 0, "same": 0, "skip": 0}
    for chapter in sorted(args.chapters):
        text = Path(chapter).read_text(encoding="utf-8")
        for rel, content in parse(text, chapter):
            target = safe(root, rel)
            if target.exists():
                if target.read_text(encoding="utf-8", errors="replace") == content:
                    counts["same"] += 1
                    continue
                if not args.force:
                    print(f"  EXISTS  {rel}  (differs; use --force to overwrite)")
                    counts["skip"] += 1
                    continue
            action = "WRITE " if args.write else "would write"
            print(f"  {action}  {rel}")
            counts["write"] += 1
            if args.write:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
                if content.startswith("#!"):
                    target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    verb = "written" if args.write else "to write (dry run; add --write)"
    print(f"{counts['write']} {verb}, {counts['same']} unchanged, {counts['skip']} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
