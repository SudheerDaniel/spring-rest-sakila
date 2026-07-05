package com.example.codebaseanalyzer.chunker;

import com.example.codebaseanalyzer.model.Chunk;
import com.example.codebaseanalyzer.model.ChunkPart;
import com.example.codebaseanalyzer.model.SourceFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-budget-aware chunking of source files.
 *
 * Strategy (mirrors the Python version's approach):
 *   1. Count tokens per file (see {@link TokenCounter}).
 *   2. Pack whole files together, greedily, into chunks up to maxTokensPerChunk,
 *      so related small files are seen together by the LLM in one call.
 *   3. Any single file that alone exceeds the budget is split further, preferring
 *      to break on blank lines or closing-brace lines (a lightweight proxy for
 *      class/method boundaries) rather than mid-statement. LangChain4j does not
 *      ship a Java-language-aware splitter equivalent to Python LangChain's
 *      `RecursiveCharacterTextSplitter.from_language(Language.JAVA)`, so this
 *      boundary-preferring heuristic is a deliberate, documented substitute.
 */
public class CodebaseChunker {

    public List<Chunk> buildChunks(List<SourceFile> files, int maxTokensPerChunk) {
        List<Chunk> chunks = new ArrayList<>();
        Chunk[] currentHolder = { new Chunk(0) };

        Runnable startNewChunk = () -> {
            if (!currentHolder[0].getParts().isEmpty()) {
                chunks.add(currentHolder[0]);
            }
            currentHolder[0] = new Chunk(chunks.size());
        };

        for (SourceFile sf : files) {
            int fileTokens = TokenCounter.count(sf.getContent());

            if (fileTokens > maxTokensPerChunk) {
                List<String> pieces = splitOversizedFile(sf.getContent(), maxTokensPerChunk);
                for (int i = 0; i < pieces.size(); i++) {
                    String piece = pieces.get(i);
                    int pieceTokens = TokenCounter.count(piece);
                    Chunk current = currentHolder[0];
                    if (!current.getParts().isEmpty() && current.getTokenCount() + pieceTokens > maxTokensPerChunk) {
                        startNewChunk.run();
                        current = currentHolder[0];
                    }
                    current.getParts().add(new ChunkPart(sf.getPath(), piece, i, pieces.size()));
                    current.addTokens(pieceTokens);
                }
                continue;
            }

            Chunk current = currentHolder[0];
            if (!current.getParts().isEmpty() && current.getTokenCount() + fileTokens > maxTokensPerChunk) {
                startNewChunk.run();
                current = currentHolder[0];
            }
            current.getParts().add(new ChunkPart(sf.getPath(), sf.getContent(), 0, 1));
            current.addTokens(fileTokens);
        }

        if (!currentHolder[0].getParts().isEmpty()) {
            chunks.add(currentHolder[0]);
        }
        return chunks;
    }

    /** Splits an oversized file's content into token-bounded pieces, preferring blank-line
     * or closing-brace boundaries so pieces don't cut off mid-statement where avoidable. */
    List<String> splitOversizedFile(String content, int maxTokensPerChunk) {
        String[] lines = content.split("\n", -1);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lastGoodBreak = -1; // index into `current`'s string length, at a preferred boundary

        for (String line : lines) {
            String candidateAddition = (current.length() == 0 ? "" : "\n") + line;
            String prospective = current + candidateAddition;

            if (TokenCounter.count(prospective) > maxTokensPerChunk && current.length() > 0) {
                if (lastGoodBreak > 0) {
                    pieces.add(current.substring(0, lastGoodBreak));
                    String remainder = current.substring(lastGoodBreak);
                    current = new StringBuilder(remainder.stripLeading());
                } else {
                    pieces.add(current.toString());
                    current = new StringBuilder();
                }
                lastGoodBreak = -1;
                current.append(current.length() == 0 ? "" : "\n").append(line);
            } else {
                current.append(candidateAddition);
            }

            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("}")) {
                lastGoodBreak = current.length();
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }

        // Safety net: if any piece is still oversized (e.g. one giant line/method with
        // no good break point), hard-split it at the token level.
        List<String> safePieces = new ArrayList<>();
        for (String piece : pieces) {
            if (TokenCounter.count(piece) <= maxTokensPerChunk) {
                safePieces.add(piece);
            } else {
                safePieces.addAll(TokenCounter.hardSplitByTokens(piece, maxTokensPerChunk));
            }
        }
        return safePieces;
    }
}
