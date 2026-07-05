package com.example.codebaseanalyzer.model;

/** One file, or one slice of an oversized file, that belongs to a {@link Chunk}. */
public class ChunkPart {
    private final String filePath;
    private final String content;
    private final int partIndex;   // 0 if the whole file fits in a single part
    private final int partTotal;

    public ChunkPart(String filePath, String content, int partIndex, int partTotal) {
        this.filePath = filePath;
        this.content = content;
        this.partIndex = partIndex;
        this.partTotal = partTotal;
    }

    public String getFilePath() { return filePath; }
    public String getContent() { return content; }
    public int getPartIndex() { return partIndex; }
    public int getPartTotal() { return partTotal; }
}
