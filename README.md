# Codebase Knowledge Extractor

A codebase analysis tool that reads a Java (or other language) codebase,
feeds it to Claude in token-safe chunks, and produces a structured JSON
representation of the extracted knowledge: project purpose, architecture,
and per-file breakdowns of responsibilities, key methods, and complexity.

Built and tested against the assignment's target codebase:
[codejsha/spring-rest-sakila](https://github.com/codejsha/spring-rest-sakila)
(a Spring Boot REST API over the Sakila sample database, ~200 source files).

**Two implementations are provided in this repo**, sharing the same
map-reduce design and output schema:

| | Language | LLM library | Status |
|---|---|---|---|
| `main.py` + `src/*.py` | Python | LangChain / LangChain-Anthropic | **Fully built, run, and verified** against the real repo with a live API key |
| `src/main/java/.../codebaseanalyzer/` | Java | LangChain4j | Scaffolded with the same architecture; not yet build-verified (see [Java section](#java-implementation) below) |

The Python version is the primary, working deliverable. The Java version
mirrors its design for teams/interviewers whose stack is Java.

---

## Python implementation (primary, verified)

### Quick start

```bash
pip install -r requirements.txt

git clone https://github.com/codejsha/spring-rest-sakila.git sakila-repo

export ANTHROPIC_API_KEY=sk-ant-...      # macOS/Linux
# or on Windows PowerShell:
# $env:ANTHROPIC_API_KEY="sk-ant-..."

python main.py --repo-path sakila-repo --output output/analysis.json
```

On Windows, if `python` isn't recognized, use `py` instead (the Python
launcher installed alongside modern Python-for-Windows installers):

```powershell
py main.py --repo-path sakila-repo --output output\analysis.json
```

### Run modes

**Real run** (calls Claude, produces genuine analysis; ~45-50 min for the
full 200-file repo based on measured timing):
```bash
python main.py --repo-path sakila-repo --output output/analysis.json
```

**Mock run** (no API key needed, no cost, runs in seconds — proves the full
pipeline mechanics: loading → chunking → extraction → reconciliation →
JSON output — using a deterministic offline stand-in instead of a real
LLM call):
```bash
python main.py --repo-path sakila-repo --output output/mock_analysis.json --mock
```

**Smoke test** (real API calls, but only the first N chunks — fast sanity
check before committing to a full run):
```bash
python main.py --repo-path sakila-repo --output output/smoke_test.json --limit-chunks 2
```

**Verbose logging** (add `-v` to any of the above for debug-level output):
```bash
python main.py --repo-path sakila-repo --output output/analysis.json -v
```

### Useful flags

| Flag | Default | Description |
|---|---|---|
| `--repo-path` | *(required)* | Path to the codebase to analyze |
| `--output` | `output/analysis.json` | Where to write the structured JSON |
| `--model` | `claude-sonnet-5` | Anthropic model name |
| `--max-tokens-per-chunk` | `6000` | Token budget per LLM call |
| `--limit-chunks` | *(none)* | Only process the first N chunks |
| `--mock` | off | Run offline with a mock extractor, no API key needed |
| `-v`, `--verbose` | off | Enable debug logging |

### Verifying output

```bash
python -c "
import json
d = json.load(open('output/analysis.json'))
print('source files:', d['metadata']['num_source_files'])
print('unique output files:', len(set(f['file_path'] for f in d['files'])))
print('recovered via reconciliation:', d['metadata']['files_recovered_via_reconciliation'])
print('still unrecoverable:', d['metadata']['files_unrecoverable'])
"
```

### Approach

Map-reduce over the codebase, chosen specifically to respect LLM token
limits without sacrificing a project-level understanding:

```
load files -> pack into token-bounded chunks -> [MAP] per-chunk LLM extraction
  -> [RECONCILE] verify + recover any files a chunk dropped
  -> [REDUCE] LLM synthesis of project overview -> JSON
```

1. **Load** (`src/loader.py`) — walks the repo, collects `.java`/`.kt`
   source files, prunes build artifacts (`build/`, `.git/`, `.gradle/`,
   `target/`, etc.) before descending into them.
2. **Chunk** (`src/chunker.py`) — counts tokens per file (via `tiktoken`'s
   `cl100k_base` encoding, with a chars-per-token fallback if the encoding
   can't be downloaded), then **greedily packs multiple small files
   together** into a single chunk up to `max_tokens_per_chunk` (default
   6000), to minimize the number of LLM calls and the repeated
   system-prompt/schema overhead each call carries. Any single file that
   alone exceeds the budget is split further with a language-aware
   splitter, falling back to a hard character-offset split as a last resort.
3. **Extract — MAP** (`src/llm_client.py`) — each chunk is sent to Claude
   via LangChain's `ChatAnthropic(...).with_structured_output(...)`, which
   binds the model's response directly to a Pydantic schema
   (`ChunkAnalysis`/`FileAnalysis`) — no manual JSON parsing of free-text
   output.
4. **Reconcile** (`src/analyzer.py` — `reconcile_missing_files()`) — **this
   step exists because of a real, measured bug**: when many small,
   structurally similar files are packed into one chunk, the model does
   not always enumerate every file it was shown. In testing against the
   full 200-file repo, initial per-chunk requests returned complete
   results 4 times out of 15 chunks and returned **zero files matched** on
   the other 11 — an all-or-nothing pattern, not a gradual drop-off. After
   every chunk response, the set of file paths actually returned is
   diffed against the set of file paths that were sent; any gap is
   re-submitted as its own single-file mini-chunk (removing the model's
   opportunity to skip anything), retried up to twice. In the full
   verified run, this recovered all 145 initially-missing files, reaching
   200/200 coverage with zero unrecoverable files.
5. **Synthesize — REDUCE** (`src/analyzer.py`) — the (much smaller) list
   of per-file summaries is combined into one additional LLM call that
   produces the project-level overview, so this step's cost stays roughly
   constant regardless of codebase size.
6. **Output** (`main.py`) — the final `CodebaseKnowledge` object (overview
   + all per-file analyses + run metadata, including reconciliation stats)
   is serialized to one JSON file via Jackson-equivalent Python `json`
   serialization.

### Known limitations

- **Reconciliation adds real runtime cost.** In the verified run, 145 of
  200 files needed individual reconciliation calls, extending total
  runtime from an expected ~15 minutes (with clean chunk-level success) to
  ~50 minutes. The most likely root cause is `max_tokens` on the
  underlying `ChatAnthropic` call being too small for a chunk containing
  15-25 files' worth of structured output — raising it is the more direct
  fix; reconciliation should be a rare safety net, not the primary
  correctness mechanism, once that's corrected.
- Reconciliation calls are currently sequential, not parallelized — a
  straightforward next improvement (a thread pool, mirroring how the
  per-file architecture below parallelizes calls) would meaningfully cut
  the reconciliation phase's wall-clock time.
- Token counting via `tiktoken` is an approximation — Anthropic doesn't
  publish a public tokenizer, so `cl100k_base` (OpenAI's encoding) is used
  as a conservative, over-counting proxy.
- Only `.java`/`.kt` files are treated as source by default (configurable
  in `loader.py`).
- No caching: re-running re-analyzes every file, even unchanged ones.

---

## Java implementation

A parallel implementation exists under
`src/main/java/com/example/codebaseanalyzer/`, mirroring the Python
design 1:1 (`loader/`, `chunker/`, `llm/`, `model/`, `schema/`,
`analyzer/`, plus a `Main.java` CLI entrypoint with the same flags,
including `--mock`), using **LangChain4j** in place of Python LangChain.

**Status, stated plainly**: this was scaffolded to demonstrate architectural
parity with the Python version, but has not yet been verified to compile
and run end-to-end in this environment — the Gradle build in particular
needs the LangChain4j dependency added to `build.gradle.kts` /
`gradle/libs.versions.toml` (not yet present), and the Gradle wrapper jar
needs to be restored if missing. Treat this as a design reference and a
starting point, not a tested deliverable, until those are resolved and a
real `./gradlew build` / `mvn clean package` succeeds.

```bash
# once the build is fixed:
./gradlew build
export ANTHROPIC_API_KEY=sk-ant-...
java -jar build/libs/codebase-analyzer.jar --repo-path sakila-repo --output output/analysis.json

# offline/mock mode, once built:
java -jar build/libs/codebase-analyzer.jar --repo-path sakila-repo --mock --output output/analysis_mock.json
```

---

## Best practices applied

- **Token-safety by construction**: chunk sizes are computed from real
  token counts before any LLM call, and oversized files are split before
  ever being sent.
- **Schema-first extraction** via structured-output binding (Pydantic in
  Python, POJOs + `@Description` in the Java design), so model output is
  validated, typed JSON — never free text requiring post-hoc parsing.
- **Map-reduce**, so the approach scales to codebases larger than a single
  context window.
- **Reconciliation as a measured, evidence-based fix**, not a
  precautionary guess — added after empirically finding and diagnosing a
  real ~72% file-drop rate in chunk-packed extraction.
- **Deterministic runs**: files processed in sorted path order,
  `temperature=0.0` for real LLM calls.
- **Testable without API cost**: `--mock` exercises the entire pipeline
  (loading, chunking, extraction interface, reconciliation, JSON writing)
  offline via a deterministic stand-in extractor.

## Assumptions & limitations (project-wide)

- Complexity ratings (Low/Medium/High) are LLM judgment calls grounded in
  the extraction prompt's stated criteria, not a computed
  cyclomatic-complexity metric.
- Designed and verified against Java codebases; other languages would
  need loader/chunker extension-list changes.
- No incremental caching — every run re-analyzes every file from scratch.
