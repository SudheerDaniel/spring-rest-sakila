package com.example.codebaseanalyzer.llm;

import com.example.codebaseanalyzer.schema.ChunkAnalysis;
import com.example.codebaseanalyzer.schema.FileAnalysis;
import com.example.codebaseanalyzer.schema.MethodInfo;
import com.example.codebaseanalyzer.schema.ProjectOverview;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, offline stand-in for {@link AnthropicExtractor}. Lets the pipeline be
 * exercised end-to-end (and unit tested) without an API key or network access. Produces
 * plausible-shaped, clearly-labeled placeholder data instead of real analysis.
 */
public class MockExtractor implements Extractor {

    @Override
    public ChunkAnalysis analyzeChunk(String chunkText) {
        List<FileAnalysis> files = new ArrayList<>();
        for (String line : chunkText.split("\n")) {
            if (line.strip().startsWith("// FILE:")) {
                String path = line.strip().replaceFirst("^// FILE:", "").strip();
                FileAnalysis fa = new FileAnalysis();
                fa.filePath = path;
                fa.packageName = null;
                fa.classNames = new ArrayList<>();
                fa.responsibility = "[MOCK] Placeholder responsibility summary generated without calling an LLM.";
                MethodInfo mi = new MethodInfo("exampleMethod", "void exampleMethod()", "[MOCK] Placeholder method description.");
                fa.keyMethods = List.of(mi);
                fa.complexity = "Medium";
                fa.complexityNotes = "[MOCK] Placeholder complexity note; run with a real API key for actual analysis.";
                files.add(fa);
            }
        }
        return new ChunkAnalysis(files);
    }

    @Override
    public ProjectOverview synthesizeOverview(String summariesText) {
        ProjectOverview overview = new ProjectOverview();
        overview.purpose = "[MOCK] Placeholder project purpose. Re-run without --mock and with a valid ANTHROPIC_API_KEY for real output.";
        overview.architectureSummary = "[MOCK] Placeholder architecture summary.";
        overview.keyModules = List.of("[MOCK] module summaries omitted");
        overview.notableTechnologies = List.of("[MOCK] technologies omitted");
        return overview;
    }
}
