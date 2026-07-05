package com.example.codebaseanalyzer.analyzer;

import com.example.codebaseanalyzer.chunker.CodebaseChunker;
import com.example.codebaseanalyzer.chunker.TokenCounter;
import com.example.codebaseanalyzer.loader.CodebaseLoader;
import com.example.codebaseanalyzer.llm.Extractor;
import com.example.codebaseanalyzer.model.Chunk;
import com.example.codebaseanalyzer.model.ChunkPart;
import com.example.codebaseanalyzer.model.SourceFile;
import com.example.codebaseanalyzer.schema.ChunkAnalysis;
import com.example.codebaseanalyzer.schema.CodebaseKnowledge;
import com.example.codebaseanalyzer.schema.FileAnalysis;
import com.example.codebaseanalyzer.schema.ProjectOverview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Top-level orchestration: load codebase -> chunk -> extract -> aggregate.
 *
 * This is a map-reduce over the codebase:
 *   MAP:    each token-bounded chunk is independently sent to the LLM to extract
 *           per-file knowledge (FileAnalysis).
 *   REDUCE: the (much smaller) per-file summaries are combined and sent in a second
 *           LLM call to synthesize one project-level overview.
 *
 * This two-pass approach lets us analyze an arbitrarily large codebase without ever
 * exceeding the LLM's context/token limits: the map pass never sees more than
 * maxTokensPerChunk tokens at a time, and the reduce pass only sees short,
 * already-condensed summaries rather than raw source.
 */
public class CodebaseAnalyzer {

    private static final Logger log = Logger.getLogger(CodebaseAnalyzer.class.getName());

    private final CodebaseLoader loader = new CodebaseLoader();
    private final CodebaseChunker chunker = new CodebaseChunker();

    public CodebaseKnowledge runAnalysis(
            String repoPath,
            Extractor extractor,
            int maxTokensPerChunk,
            String modelName,
            Integer limitChunks
    ) {
        log.info("Loading source files from " + repoPath);
        List<SourceFile> files = loader.loadSourceFiles(repoPath);
        log.info("Found " + files.size() + " source files");

        List<Chunk> chunks = chunker.buildChunks(files, maxTokensPerChunk);
        log.info("Built " + chunks.size() + " chunk(s) (max " + maxTokensPerChunk + " tokens each)");

        if (limitChunks != null && limitChunks < chunks.size()) {
            chunks = chunks.subList(0, limitChunks);
            log.info("Limiting to first " + chunks.size() + " chunk(s) for this run");
        }

        List<FileAnalysis> allFileAnalyses = new ArrayList<>();
        for (Chunk chunk : chunks) {
            String chunkText = renderChunkText(chunk);
            log.info(String.format("Analyzing chunk %d/%d (%d files, ~%d tokens)",
                    chunk.getIndex() + 1, chunks.size(), chunk.getFilePaths().size(), TokenCounter.count(chunkText)));
            ChunkAnalysis result = extractor.analyzeChunk(chunkText);
            allFileAnalyses.addAll(result.files);
        }

        StringBuilder summaries = new StringBuilder();
        for (FileAnalysis fa : allFileAnalyses) {
            summaries.append("- ").append(fa.filePath).append(": ").append(fa.responsibility).append("\n");
        }

        log.info("Synthesizing project overview from " + allFileAnalyses.size() + " file summaries");
        ProjectOverview overview = extractor.synthesizeOverview(summaries.toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", modelName);
        metadata.put("sourceRepoPath", repoPath);
        metadata.put("numSourceFiles", files.size());
        metadata.put("numChunks", chunks.size());
        metadata.put("maxTokensPerChunk", maxTokensPerChunk);
        metadata.put("generatedAtUtc", Instant.now().toString());

        return new CodebaseKnowledge(overview, allFileAnalyses, metadata);
    }

    /** Render a Chunk into the text block sent to the LLM, with clear file markers so the
     * model (and the mock extractor) can attribute output back to the right file. */
    public static String renderChunkText(Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        for (ChunkPart part : chunk.getParts()) {
            String label = part.getFilePath();
            if (part.getPartTotal() > 1) {
                label += "  (part " + (part.getPartIndex() + 1) + "/" + part.getPartTotal() + ")";
            }
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("// FILE: ").append(label).append("\n").append(part.getContent());
        }
        return sb.toString();
    }
}
