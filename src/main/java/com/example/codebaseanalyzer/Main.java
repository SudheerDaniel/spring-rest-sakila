package com.example.codebaseanalyzer;

import com.example.codebaseanalyzer.analyzer.CodebaseAnalyzer;
import com.example.codebaseanalyzer.llm.AnthropicExtractor;
import com.example.codebaseanalyzer.llm.Extractor;
import com.example.codebaseanalyzer.llm.MockExtractor;
import com.example.codebaseanalyzer.schema.CodebaseKnowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI entrypoint for the LLM-powered codebase knowledge extractor.
 *
 * Usage:
 *   export ANTHROPIC_API_KEY=sk-ant-...
 *   java -jar codebase-analyzer.jar --repo-path /path/to/spring-rest-sakila --output output/analysis.json
 *
 *   # Or, to exercise the full pipeline without an API key:
 *   java -jar codebase-analyzer.jar --repo-path /path/to/spring-rest-sakila --mock --output output/analysis_mock.json
 */
public class Main {

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = parseArgs(args);
        loadDotEnvIfPresent();

        if (opts.containsKey("help")) {
            printUsage();
            return;
        }

        String repoPath = opts.get("repo-path");
        if (repoPath == null) {
            System.err.println("ERROR: --repo-path is required.");
            printUsage();
            System.exit(1);
            return;
        }

        String output = opts.getOrDefault("output", "output/analysis.json");
        String model = opts.getOrDefault("model", "claude-sonnet-5");
        int maxTokensPerChunk = Integer.parseInt(opts.getOrDefault("max-tokens-per-chunk", "6000"));
        Integer limitChunks = opts.containsKey("limit-chunks") ? Integer.parseInt(opts.get("limit-chunks")) : null;
        boolean mock = opts.containsKey("mock");
        boolean verbose = opts.containsKey("v") || opts.containsKey("verbose");

        configureLogging(verbose);

        Extractor extractor;
        String modelNameForMetadata;
        if (mock) {
            extractor = new MockExtractor();
            modelNameForMetadata = "mock";
        } else {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getProperty("ANTHROPIC_API_KEY");
            }
            if (apiKey == null || apiKey.isBlank()) {
                System.err.println("ERROR: ANTHROPIC_API_KEY is not set. Export it, put it in a .env file, " +
                        "or pass --mock to run offline.");
                System.exit(1);
                return;
            }
            extractor = new AnthropicExtractor(apiKey, model, 0.0, 3);
            modelNameForMetadata = model;
        }

        CodebaseAnalyzer analyzer = new CodebaseAnalyzer();
        CodebaseKnowledge knowledge = analyzer.runAnalysis(repoPath, extractor, maxTokensPerChunk, modelNameForMetadata, limitChunks);

        Path outputPath = Paths.get(output);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), knowledge);

        System.out.println("Wrote structured analysis to " + output);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") && !arg.equals("-v")) continue;
            String key = arg.equals("-v") ? "v" : arg.substring(2);
            if (key.equals("mock") || key.equals("verbose") || key.equals("v") || key.equals("help")) {
                opts.put(key, "true");
            } else if (i + 1 < args.length) {
                opts.put(key, args[++i]);
            }
        }
        return opts;
    }

    /** Minimal .env loader (mirrors python-dotenv) so the API key never needs to be hardcoded. */
    private static void loadDotEnvIfPresent() {
        Path envFile = Paths.get(".env");
        if (!Files.exists(envFile)) return;
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                int idx = trimmed.indexOf('=');
                String key = trimmed.substring(0, idx).strip();
                String value = trimmed.substring(idx + 1).strip();
                if (System.getenv(key) == null) {
                    System.setProperty(key, value); // best-effort; real env var takes precedence
                }
            }
        } catch (IOException ignored) {
            // .env is optional; ignore read errors
        }
    }

    private static void configureLogging(boolean verbose) {
        Logger root = Logger.getLogger("");
        root.setLevel(verbose ? Level.FINE : Level.INFO);
        for (var handler : root.getHandlers()) {
            handler.setLevel(verbose ? Level.FINE : Level.INFO);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -jar codebase-analyzer.jar --repo-path <path> [options]

                Options:
                  --repo-path <path>              Path to the local checkout of the codebase to analyze (required)
                  --output <path>                 Output JSON path (default: output/analysis.json)
                  --model <name>                   Anthropic model name (default: claude-sonnet-5)
                  --max-tokens-per-chunk <n>       Token budget per LLM call (default: 6000)
                  --limit-chunks <n>               Only process the first N chunks (smoke testing)
                  --mock                           Run offline with a mock extractor, no API key needed
                  -v, --verbose                    Enable debug logging
                """);
    }
}
