package com.example.codebaseanalyzer.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A token-bounded group of {@link ChunkPart}s that will be sent to the LLM in one call. */
public class Chunk {
    private final int index;
    private final List<ChunkPart> parts = new ArrayList<>();
    private int tokenCount = 0;

    public Chunk(int index) {
        this.index = index;
    }

    public int getIndex() { return index; }
    public List<ChunkPart> getParts() { return parts; }
    public int getTokenCount() { return tokenCount; }
    public void addTokens(int n) { this.tokenCount += n; }

    public Set<String> getFilePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (ChunkPart p : parts) paths.add(p.getFilePath());
        return paths;
    }
}
