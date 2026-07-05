package com.example.codebaseanalyzer.model;

/** A single source file read from disk, relative to the repository root. */
public class SourceFile {
    private final String path;
    private final String content;
    private final int sizeBytes;

    public SourceFile(String path, String content) {
        this.path = path;
        this.content = content;
        this.sizeBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public String getPath() { return path; }
    public String getContent() { return content; }
    public int getSizeBytes() { return sizeBytes; }
}
