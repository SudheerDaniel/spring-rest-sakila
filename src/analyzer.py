"""Top-level orchestration: load codebase -> chunk -> extract -> aggregate.

This is a map-reduce over the codebase:
  MAP:    each token-bounded chunk is independently sent to the LLM to
          extract per-file knowledge (schema.FileAnalysis).
  REDUCE: the (much smaller) per-file summaries are combined and sent in a
          second LLM call to synthesize one project-level overview.

This two-pass approach is what lets us analyze an arbitrarily large
codebase without ever exceeding the LLM's context/token limits: pass 1
never sees more than `max_tokens_per_chunk` tokens at a time, and pass 2
only sees short, already-condensed summaries rather than raw source.
"""
from __future__ import annotations

import datetime
import logging
from typing import List

from src.chunker import Chunk, build_chunks, count_tokens
from src.loader import load_source_files
from src.schema import CodebaseKnowledge, FileAnalysis

logger = logging.getLogger(__name__)


def render_chunk_text(chunk: Chunk) -> str:
    """Render a Chunk into the text block sent to the LLM, with clear file
    markers so the model (and our mock extractor) can attribute output back
    to the right file.
    """
    sections = []
    for part in chunk.parts:
        label = part.file_path
        if part.part_total > 1:
            label += f"  (part {part.part_index + 1}/{part.part_total})"
        sections.append(f"// FILE: {label}\n{part.content}")
    return "\n\n".join(sections)


def run_analysis(
    repo_path: str,
    extractor,
    max_tokens_per_chunk: int = 6000,
    model_name: str = "unknown",
    limit_chunks: int | None = None,
) -> CodebaseKnowledge:
    logger.info("Loading source files from %s", repo_path)
    files = load_source_files(repo_path)
    logger.info("Found %d source files", len(files))

    chunks = build_chunks(files, max_tokens_per_chunk=max_tokens_per_chunk)
    logger.info("Built %d chunk(s) (max %d tokens each)", len(chunks), max_tokens_per_chunk)

    if limit_chunks is not None:
        chunks = chunks[:limit_chunks]
        logger.info("Limiting to first %d chunk(s) for this run", len(chunks))

    all_file_analyses: List[FileAnalysis] = []
    for chunk in chunks:
        chunk_text = render_chunk_text(chunk)
        logger.info(
            "Analyzing chunk %d/%d (%d files, ~%d tokens)",
            chunk.index + 1, len(chunks), len(chunk.file_paths), count_tokens(chunk_text),
        )
        result = extractor.analyze_chunk(chunk_text)
        all_file_analyses.extend(result.files)

    # Reduce step: synthesize a project-level overview from the per-file summaries.
    summaries_text = "\n".join(
        f"- {fa.file_path}: {fa.responsibility}" for fa in all_file_analyses
    )
    logger.info("Synthesizing project overview from %d file summaries", len(all_file_analyses))
    overview = extractor.synthesize_overview(summaries_text)

    knowledge = CodebaseKnowledge(
        project_overview=overview,
        files=all_file_analyses,
        metadata={
            "model": model_name,
            "source_repo_path": repo_path,
            "num_source_files": len(files),
            "num_chunks": len(chunks),
            "max_tokens_per_chunk": max_tokens_per_chunk,
            "generated_at_utc": datetime.datetime.utcnow().isoformat() + "Z",
        },
    )
    return knowledge
