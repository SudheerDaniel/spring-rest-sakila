# Codebase Knowledge Extractor (LLM-powered)

A program that reads a codebase, feeds it to an LLM in token-safe chunks,
and produces a structured JSON representation of the extracted knowledge:
project purpose, architecture, and per-file breakdowns of responsibilities,
key methods, and complexity.

Built and tested against the target codebase for this assignment:
[`codejsha/spring-rest-sakila`](https://github.com/codejsha/spring-rest-sakila)
(a Spring Boot REST API over the Sakila sample database).

## Approach

The pipeline is a **map-reduce over the codebase**, chosen specifically to
respect LLM token limits without sacrificing a project-level understanding:

```
load files -> build token-bounded chunks -> [MAP] per-chunk LLM extraction -> [REDUCE] LLM synthesis of overview -> JSON
```

1. **Load** (`src/loader.py`) — walks the repo, collecting `.java`/`.kt`
   source files and skipping build artifacts (`build/`, `.git/`,
   `.gradle/`, etc.). Kept dependency-free and unit-testable on its own.

2. **Chunk** (`src/chunker.py`) — counts tokens per file (via `tiktoken`'s
   `cl100k_base` encoding, used as a practical proxy since Anthropic does
   not publish a local tokenizer — see *Assumptions* below) and greedily
   packs whole files together up to a configurable `max_tokens_per_chunk`
   (default 6000). Any single file that alone exceeds the budget is split
   further using LangChain's `RecursiveCharacterTextSplitter.from_language(Language.JAVA)`,
   which prefers breaking on class/method boundaries over arbitrary line
   cuts, falling back to a hard token-level split as a safety net.

3. **Extract (MAP)** (`src/llm_client.py`, `AnthropicExtractor`) — each
   chunk is sent to Claude via **LangChain's `ChatAnthropic`** chat model
   using `.with_structured_output(ChunkAnalysis)`. This binds the model's
   response to our Pydantic schema (`src/schema.py`) directly, so every
   response is already-validated, consistent JSON — no manual regex/JSON
   parsing of free-text LLM output.

4. **Synthesize (REDUCE)** (`src/analyzer.py`) — the (much smaller) list of
   per-file responsibility summaries from step 3 is combined into one
   prompt and sent for a **second** LLM call that produces the project-level
   overview (purpose, architecture, key modules, technologies). This keeps
   the "big picture" call small regardless of how large the codebase is,
   since it only ever sees condensed summaries, never raw source.

5. **Output** (`main.py`) — the final `CodebaseKnowledge` object (overview +
   all per-file analyses + run metadata) is serialized to a single JSON file.

## Project layout

```
codebase-analyzer/
├── main.py                 # CLI entrypoint
├── requirements.txt
├── .env.example
├── src/
│   ├── loader.py            # walk repo -> List[SourceFile]
│   ├── chunker.py            # token-aware chunking/splitting
│   ├── schema.py            # Pydantic output schema
│   ├── llm_client.py         # LangChain/Anthropic extractor + offline MockExtractor
│   └── analyzer.py          # map-reduce orchestration
├── tests/
│   └── test_pipeline.py      # unit tests for loader + chunker (no network/LLM needed)
└── output/
    └── analysis.json         # sample structured output (see note below)
```

## Running it

```bash
pip install -r requirements.txt

# Real run (requires an Anthropic API key)
export ANTHROPIC_API_KEY=sk-ant-...
python main.py --repo-path /path/to/spring-rest-sakila --output output/analysis.json

# Offline smoke test / demo of the pipeline mechanics, no API key needed
python main.py --repo-path /path/to/spring-rest-sakila --mock --output output/analysis_mock.json

# Unit tests (no network/API key needed)
python -m pytest tests/ -v
```

Useful flags: `--model` (default `claude-sonnet-4-6`), `--max-tokens-per-chunk`
(default `6000`), `--limit-chunks N` (process only the first N chunks, handy
for a quick smoke test on a large repo), `-v` for debug logging.

## Output schema

```jsonc
{
  "project_overview": {
    "purpose": "...",
    "architecture_summary": "...",
    "key_modules": ["..."],
    "notable_technologies": ["..."]
  },
  "files": [
    {
      "file_path": "src/main/java/.../FilmController.java",
      "package": "com.example.app.services.catalog.controller",
      "class_names": ["FilmController"],
      "responsibility": "...",
      "key_methods": [
        {"name": "getFilm", "signature": "ResponseEntity<FilmDto> getFilm(Long id)", "description": "..."}
      ],
      "complexity": "Low | Medium | High",
      "complexity_notes": "..."
    }
  ],
  "metadata": {
    "model": "claude-sonnet-4-6",
    "source_repo_path": "...",
    "num_source_files": 202,
    "num_chunks": 20,
    "max_tokens_per_chunk": 6000,
    "generated_at_utc": "..."
  }
}
```

## Best practices applied

- **Token-safety by construction**, not by hoping the model tolerates a big
  prompt: chunk sizes are computed from actual token counts, and any file
  large enough to blow the budget alone is split before it's ever sent.
- **Schema-first extraction** via `with_structured_output`, so the model's
  output is validated JSON, not free text that needs post-hoc parsing.
- **Map-reduce**, so the approach scales to codebases much larger than a
  single context window — the reduce step only ever processes condensed
  summaries.
- **Deterministic runs**: files are processed in sorted path order, and
  `temperature=0.0` for the real LLM calls, so re-running on the same repo
  produces closely comparable output.
- **Testable without a paid API key**: `loader`/`chunker` are pure Python
  with unit tests, and a `MockExtractor` lets you exercise the entire
  pipeline (including JSON I/O) offline via `--mock`.
- **Retry with backoff** around LLM calls for transient API/network errors.

## Assumptions & limitations

- **Language scope**: only `.java`/`.kt` files are analyzed (configurable
  in `loader.py`). Build files, SQL, YAML config, and docs are intentionally
  out of scope for this assignment's "code comprehension" goal.
- **Token counting is an approximation.** Anthropic does not publish a
  local tokenizer, so `tiktoken`'s `cl100k_base` (OpenAI's encoding) is used
  as a practical, widely-used proxy. It tends to over-count relative to
  Claude's actual tokenizer, which errs safely (chunks end up smaller than
  strictly necessary rather than risking an overflow).
- **Cost/latency**: one real run over the full ~200-file Sakila codebase
  makes ~21 LLM calls (20 extraction chunks + 1 overview synthesis) at the
  default 6000-token chunk size.
- **`output/analysis.json` in this deliverable was generated with `--mock`**
  (no LLM calls), so its `responsibility`/`key_methods`/`complexity_notes`
  fields are clearly-labeled placeholders — it exists to prove the full
  pipeline (loading → chunking → extraction interface → aggregation → JSON
  writing) runs end-to-end and produces schema-valid output. Running the
  same command without `--mock` and with a valid `ANTHROPIC_API_KEY`
  produces the real analysis in the identical shape.
- **No incremental/caching support**: each run re-analyzes every file; a
  production version would hash file contents and skip unchanged files on
  subsequent runs.
