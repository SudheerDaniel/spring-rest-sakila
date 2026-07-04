#!/usr/bin/env python3
"""CLI entrypoint for the LLM-powered codebase knowledge extractor.

Usage:
    export ANTHROPIC_API_KEY=sk-ant-...
    python main.py --repo-path /path/to/spring-rest-sakila --output output/analysis.json

    # Or, to exercise the full pipeline without an API key:
    python main.py --repo-path /path/to/spring-rest-sakila --mock --output output/analysis_mock.json
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys

from dotenv import load_dotenv

from src.analyzer import run_analysis


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract structured knowledge from a codebase using an LLM.")
    parser.add_argument("--repo-path", required=True, help="Path to the local checkout of the codebase to analyze.")
    parser.add_argument("--output", default="output/analysis.json", help="Path to write the resulting JSON file.")
    parser.add_argument("--model", default="claude-sonnet-4-6", help="Anthropic model name to use.")
    parser.add_argument("--max-tokens-per-chunk", type=int, default=6000, help="Token budget per LLM call for the extraction pass.")
    parser.add_argument("--limit-chunks", type=int, default=None, help="Only process the first N chunks (useful for quick smoke tests).")
    parser.add_argument("--mock", action="store_true", help="Run the full pipeline with a mock (offline) extractor instead of calling the real LLM.")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging.")
    return parser.parse_args()


def main() -> int:
    load_dotenv()
    args = parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
    )

    if args.mock:
        from src.llm_client import MockExtractor
        extractor = MockExtractor()
        model_name = "mock"
    else:
        if not os.environ.get("ANTHROPIC_API_KEY"):
            print("ERROR: ANTHROPIC_API_KEY is not set. Export it, put it in a .env file, or pass --mock to run offline.", file=sys.stderr)
            return 1
        from src.llm_client import AnthropicExtractor
        extractor = AnthropicExtractor(model=args.model)
        model_name = args.model

    knowledge = run_analysis(
        repo_path=args.repo_path,
        extractor=extractor,
        max_tokens_per_chunk=args.max_tokens_per_chunk,
        model_name=model_name,
        limit_chunks=args.limit_chunks,
    )

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(knowledge.model_dump(), f, indent=2, ensure_ascii=False)

    print(f"Wrote structured analysis to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
