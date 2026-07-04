"""LLM interaction layer.

Uses LangChain's `ChatAnthropic` chat model with `.with_structured_output(...)`
so the model's response is validated against our Pydantic schema
(`ChunkAnalysis` / `ProjectOverview`) automatically -- this is what keeps the
output "structured, consistent, and machine-readable" as required by the
assignment, without us hand-rolling JSON parsing/regex.

A `MockLLM` is also provided so the full pipeline (loading -> chunking ->
extraction -> aggregation -> JSON writing) can be run and tested end-to-end
without an API key or network access -- useful for CI and for reviewers who
want to see the mechanism work before wiring up their own key.
"""
from __future__ import annotations

import time
from typing import List

from langchain_core.prompts import ChatPromptTemplate

from src.schema import ChunkAnalysis, FileAnalysis, MethodInfo, ProjectOverview

CHUNK_SYSTEM_PROMPT = """\
You are a senior software engineer performing static code analysis.
You will be given the contents of one or more source files (a "chunk") from \
a single codebase. For EACH file present in the chunk, extract:
  - its package name (if any)
  - the top-level class/interface/enum name(s) it declares
  - a one-paragraph description of the file's responsibility
  - its most important methods, with full signatures and short descriptions
  - a complexity rating (Low, Medium, or High) with a short justification, \
noting any noteworthy design patterns, risks, or code smells

If a file appears only partially (it was split due to size), analyze the \
portion you can see and note in your response that it is a partial view.

Respond ONLY with the structured data requested -- no extra commentary.
"""

CHUNK_USER_TEMPLATE = """Chunk contents:

{chunk_text}
"""

OVERVIEW_SYSTEM_PROMPT = """\
You are a senior software architect. You will be given a list of short, \
per-file summaries that were extracted from a codebase. Using ONLY this \
information, produce a high-level project overview: overall purpose, \
architecture/layering summary, notable modules, and key technologies used. \
Respond ONLY with the structured data requested.
"""

OVERVIEW_USER_TEMPLATE = """Per-file summaries:

{summaries_text}
"""


class AnthropicExtractor:
    """Real LLM-backed extractor using LangChain + Anthropic's Claude models."""

    def __init__(self, model: str = "claude-sonnet-4-6", temperature: float = 0.0, max_retries: int = 3):
        from langchain_anthropic import ChatAnthropic  # imported lazily so mock mode needs no key/network

        self._llm = ChatAnthropic(model=model, temperature=temperature, max_tokens=4096)
        self._chunk_prompt = ChatPromptTemplate.from_messages(
            [("system", CHUNK_SYSTEM_PROMPT), ("user", CHUNK_USER_TEMPLATE)]
        )
        self._overview_prompt = ChatPromptTemplate.from_messages(
            [("system", OVERVIEW_SYSTEM_PROMPT), ("user", OVERVIEW_USER_TEMPLATE)]
        )
        self._chunk_chain = self._chunk_prompt | self._llm.with_structured_output(ChunkAnalysis)
        self._overview_chain = self._overview_prompt | self._llm.with_structured_output(ProjectOverview)
        self.max_retries = max_retries

    def analyze_chunk(self, chunk_text: str) -> ChunkAnalysis:
        return self._invoke_with_retry(self._chunk_chain, {"chunk_text": chunk_text})

    def synthesize_overview(self, summaries_text: str) -> ProjectOverview:
        return self._invoke_with_retry(self._overview_chain, {"summaries_text": summaries_text})

    def _invoke_with_retry(self, chain, inputs: dict):
        last_err = None
        for attempt in range(1, self.max_retries + 1):
            try:
                return chain.invoke(inputs)
            except Exception as e:  # network hiccups / transient API errors
                last_err = e
                time.sleep(min(2 ** attempt, 10))
        raise RuntimeError(f"LLM call failed after {self.max_retries} attempts: {last_err}")


class MockExtractor:
    """Deterministic, offline stand-in for AnthropicExtractor.

    Lets the pipeline be exercised end-to-end (and unit tested) without an
    API key or network access. Produces plausible-shaped, clearly-labeled
    placeholder data instead of real analysis.
    """

    def analyze_chunk(self, chunk_text: str) -> ChunkAnalysis:
        # Very cheap heuristic parse just so mock output isn't pure noise:
        # pull "public/private/protected ... class Foo" and package lines.
        files: List[FileAnalysis] = []
        pseudo_path = "unknown"
        for line in chunk_text.splitlines():
            if line.strip().startswith("// FILE:"):
                pseudo_path = line.strip().replace("// FILE:", "").strip()
                files.append(
                    FileAnalysis(
                        file_path=pseudo_path,
                        package=None,
                        class_names=[],
                        responsibility="[MOCK] Placeholder responsibility summary generated without calling an LLM.",
                        key_methods=[
                            MethodInfo(
                                name="exampleMethod",
                                signature="void exampleMethod()",
                                description="[MOCK] Placeholder method description.",
                            )
                        ],
                        complexity="Medium",
                        complexity_notes="[MOCK] Placeholder complexity note; run with a real API key for actual analysis.",
                    )
                )
        return ChunkAnalysis(files=files)

    def synthesize_overview(self, summaries_text: str) -> ProjectOverview:
        return ProjectOverview(
            purpose="[MOCK] Placeholder project purpose. Run with --mock disabled and a valid ANTHROPIC_API_KEY for real output.",
            architecture_summary="[MOCK] Placeholder architecture summary.",
            key_modules=["[MOCK] module summaries omitted"],
            notable_technologies=["[MOCK] technologies omitted"],
        )
