package com.example.codebaseanalyzer.schema;

import java.util.ArrayList;
import java.util.List;

/** Raw result returned by the LLM for a single chunk (one or more files). */
public class ChunkAnalysis {
    public List<FileAnalysis> files = new ArrayList<>();

    public ChunkAnalysis() { }

    public ChunkAnalysis(List<FileAnalysis> files) {
        this.files = files;
    }
}
