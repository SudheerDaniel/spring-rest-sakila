import os
import sys
import tempfile

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.chunker import build_chunks, count_tokens
from src.loader import load_source_files


def _write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(content)


def test_loader_finds_java_files_and_skips_build_dirs():
    with tempfile.TemporaryDirectory() as tmp:
        _write(os.path.join(tmp, "src/main/java/Foo.java"), "public class Foo {}")
        _write(os.path.join(tmp, "build/generated/Ignored.java"), "public class Ignored {}")
        _write(os.path.join(tmp, "README.md"), "not source")

        files = load_source_files(tmp)
        paths = {f.path for f in files}

        assert "src/main/java/Foo.java" in paths
        assert not any("build" in p for p in paths)
        assert not any(p.endswith(".md") for p in paths)


def test_chunker_packs_small_files_together():
    with tempfile.TemporaryDirectory() as tmp:
        for i in range(5):
            _write(os.path.join(tmp, f"src/File{i}.java"), f"public class File{i} {{ void m() {{}} }}")
        files = load_source_files(tmp)
        chunks = build_chunks(files, max_tokens_per_chunk=6000)

        assert len(chunks) == 1
        assert len(chunks[0].file_paths) == 5


def test_chunker_splits_oversized_file():
    with tempfile.TemporaryDirectory() as tmp:
        big_method_body = "\n".join(f"        int x{i} = {i};" for i in range(2000))
        content = f"public class Big {{\n    void m() {{\n{big_method_body}\n    }}\n}}"
        _write(os.path.join(tmp, "src/Big.java"), content)

        files = load_source_files(tmp)
        assert count_tokens(files[0].content) > 500  # sanity check it's actually big

        chunks = build_chunks(files, max_tokens_per_chunk=500)
        assert len(chunks) >= 2
        for chunk in chunks:
            assert chunk.token_count <= 500 * 1.05  # small tolerance for boundary rounding


def test_chunk_respects_token_budget_across_many_files():
    with tempfile.TemporaryDirectory() as tmp:
        for i in range(50):
            _write(os.path.join(tmp, f"src/F{i}.java"), "public class C {\n" + ("    int a;\n" * 50) + "}")
        files = load_source_files(tmp)
        chunks = build_chunks(files, max_tokens_per_chunk=1000)

        assert len(chunks) > 1
        for chunk in chunks:
            assert chunk.token_count <= 1000 * 1.05


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__, "-v"]))
