"""Reads a codebase from disk into a list of (relative_path, content) tuples.

Keeps the reading mechanism deliberately simple and dependency-free so it
can be reasoned about and unit tested without any LLM/network involvement.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import List

# File extensions worth sending to the LLM for this assignment. Kept
# configurable so the same loader works for other codebases too.
DEFAULT_INCLUDE_EXTENSIONS = {".java", ".kt"}

# Directories that never contain hand-written source we care about.
DEFAULT_EXCLUDE_DIRS = {
    ".git", ".gradle", "build", "out", "target",
    "node_modules", ".idea", ".vscode", "bin",
}


@dataclass
class SourceFile:
    path: str          # path relative to repo root, e.g. "src/main/java/.../Foo.java"
    content: str
    size_bytes: int


def load_source_files(
    repo_path: str,
    include_extensions: set[str] = None,
    exclude_dirs: set[str] = None,
) -> List[SourceFile]:
    """Walk `repo_path` and return every matching source file, sorted by path
    for deterministic, reproducible output.
    """
    include_extensions = include_extensions or DEFAULT_INCLUDE_EXTENSIONS
    exclude_dirs = exclude_dirs or DEFAULT_EXCLUDE_DIRS

    results: List[SourceFile] = []
    repo_path = os.path.abspath(repo_path)

    for root, dirs, files in os.walk(repo_path):
        # prune excluded directories in-place so os.walk doesn't descend into them
        dirs[:] = [d for d in dirs if d not in exclude_dirs and not d.startswith(".")]

        for fname in files:
            _, ext = os.path.splitext(fname)
            if ext.lower() not in include_extensions:
                continue
            abs_path = os.path.join(root, fname)
            rel_path = os.path.relpath(abs_path, repo_path)
            try:
                with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
            except OSError:
                continue
            results.append(SourceFile(path=rel_path, content=content, size_bytes=len(content.encode("utf-8"))))

    results.sort(key=lambda f: f.path)
    return results
