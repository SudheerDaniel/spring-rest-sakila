package com.example.codebaseanalyzer.llm;

import com.example.codebaseanalyzer.schema.ChunkAnalysis;
import com.example.codebaseanalyzer.schema.ProjectOverview;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;

/** Real LLM-backed extractor using LangChain4j's Anthropic integration. */
public class AnthropicExtractor implements Extractor {

    private final CodeAnalysisAssistants.ChunkExtractionAssistant chunkAssistant;
    private final CodeAnalysisAssistants.OverviewSynthesisAssistant overviewAssistant;
    private final int maxRetries;

    public AnthropicExtractor(String apiKey, String modelName, double temperature, int maxRetries) {
        ChatLanguageModel model = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(4096)
                .build();

        this.chunkAssistant = AiServices.create(CodeAnalysisAssistants.ChunkExtractionAssistant.class, model);
        this.overviewAssistant = AiServices.create(CodeAnalysisAssistants.OverviewSynthesisAssistant.class, model);
        this.maxRetries = maxRetries;
    }

    @Override
    public ChunkAnalysis analyzeChunk(String chunkText) {
        return invokeWithRetry(() -> chunkAssistant.analyze(chunkText));
    }

    @Override
    public ProjectOverview synthesizeOverview(String summariesText) {
        return invokeWithRetry(() -> overviewAssistant.synthesize(summariesText));
    }

    private <T> T invokeWithRetry(java.util.function.Supplier<T> call) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                try {
                    Thread.sleep(Math.min((long) Math.pow(2, attempt) * 1000, 10_000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new RuntimeException("LLM call failed after " + maxRetries + " attempts", lastError);
    }
}
