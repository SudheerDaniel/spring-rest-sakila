"""Token-budget-aware chunking of source files.

Strategy (documented in the README under "Approach"):
  1. Count tokens per file using tiktoken as an approximation of Claude's
     tokenizer (Anthropic does not publish a local tokenizer; cl100k_base
     is a reasonable, widely-used proxy and errs on the side of
     over-counting, which keeps us safely under real limits).
  2. Pack whole files together, greedily, into chunks up to
     `max_tokens_per_chunk`, so the LLM sees related small files together
     for better context and fewer calls (efficiency best practice).
  3. Any single file that alone exceeds the budget is split further with
     LangChain's language-aware `RecursiveCharacterTextSplitter`, which
     tries to break on class/method boundaries rather than mid-statement.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List

import logging

import tiktoken
from langchain_text_splitters import Language, RecursiveCharacterTextSplitter

from src.loader import SourceFile

logger = logging.getLogger(__name__)

try:
    _ENCODING = tiktoken.get_encoding("cl100k_base")
except Exception as e:  # e.g. no network access to fetch the vocab file on first run
    logger.warning(
        "Could not load tiktoken's cl100k_base encoding (%s). "
        "Falling back to a chars/4 token-count heuristic; counts will be less precise.",
        e,
    )
    _ENCODING = None

_CHARS_PER_TOKEN_HEURISTIC = 4  # rough average for source code, used only if tiktoken is unavailable


def count_tokens(text: str) -> int:
    if _ENCODING is not None:
        return len(_ENCODING.encode(text, disallowed_special=()))
    return max(1, len(text) // _CHARS_PER_TOKEN_HEURISTIC)


def _encode(text: str):
    if _ENCODING is not None:
        return _ENCODING.encode(text, disallowed_special=())
    return None


def _decode(tokens) -> str:
    if _ENCODING is not None:
        return _ENCODING.decode(tokens)
    raise RuntimeError("decode() requires tiktoken; not available in fallback mode")


@dataclass
class ChunkPart:
    """One file, or one slice of a too-large file, inside a Chunk."""
    file_path: str
    content: str
    part_index: int = 0   # 0 if the whole file fits in one part
    part_total: int = 1


@dataclass
class Chunk:
    index: int
    parts: List[ChunkPart] = field(default_factory=list)
    token_count: int = 0

    @property
    def file_paths(self) -> List[str]:
        return sorted({p.file_path for p in self.parts})


def _split_oversized_file(sf: SourceFile, max_tokens_per_chunk: int) -> List[str]:
    """Split a single file's content into pieces that individually fit the budget,
    preferring Java syntactic boundaries (class/method) over arbitrary line cuts.
    """
    splitter = RecursiveCharacterTextSplitter.from_language(
        language=Language.JAVA,
        chunk_size=max_tokens_per_chunk * 4,   # rough chars-per-token heuristic as a starting point
        chunk_overlap=0,
        length_function=count_tokens,
    )
    pieces = splitter.split_text(sf.content)
    # Guard: if the heuristic still produced an oversized piece, hard-split it.
    safe_pieces: List[str] = []
    for piece in pieces:
        if count_tokens(piece) <= max_tokens_per_chunk:
            safe_pieces.append(piece)
        elif _ENCODING is not None:
            tokens = _encode(piece)
            for i in range(0, len(tokens), max_tokens_per_chunk):
                safe_pieces.append(_decode(tokens[i:i + max_tokens_per_chunk]))
        else:
            # fallback: hard-split by characters using the heuristic ratio
            max_chars = max_tokens_per_chunk * _CHARS_PER_TOKEN_HEURISTIC
            for i in range(0, len(piece), max_chars):
                safe_pieces.append(piece[i:i + max_chars])
    return safe_pieces


def build_chunks(files: List[SourceFile], max_tokens_per_chunk: int = 6000) -> List[Chunk]:
    """Greedily pack files into token-bounded chunks, splitting oversized files."""
    chunks: List[Chunk] = []
    current = Chunk(index=0)

    def start_new_chunk():
        nonlocal current
        if current.parts:
            chunks.append(current)
        current = Chunk(index=len(chunks))

    for sf in files:
        file_tokens = count_tokens(sf.content)

        if file_tokens > max_tokens_per_chunk:
            # Oversized file: split it, and give each slice its own chunk(s).
            pieces = _split_oversized_file(sf, max_tokens_per_chunk)
            for i, piece in enumerate(pieces):
                piece_tokens = count_tokens(piece)
                if current.parts and current.token_count + piece_tokens > max_tokens_per_chunk:
                    start_new_chunk()
                current.parts.append(ChunkPart(file_path=sf.path, content=piece, part_index=i, part_total=len(pieces)))
                current.token_count += piece_tokens
            continue

        if current.parts and current.token_count + file_tokens > max_tokens_per_chunk:
            start_new_chunk()

        current.parts.append(ChunkPart(file_path=sf.path, content=sf.content))
        current.token_count += file_tokens

    if current.parts:
        chunks.append(current)

    return chunks
