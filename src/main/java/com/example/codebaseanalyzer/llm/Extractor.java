package com.example.codebaseanalyzer.llm;

import com.example.codebaseanalyzer.schema.ChunkAnalysis;
import com.example.codebaseanalyzer.schema.ProjectOverview;

/** Abstraction over "analyze one chunk" and "synthesize the project overview",
 * implemented by both a real, Anthropic-backed extractor and an offline mock. */
public interface Extractor {
    ChunkAnalysis analyzeChunk(String chunkText);
    ProjectOverview synthesizeOverview(String summariesText);
}
