package com.example.codebaseanalyzer.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final structured deliverable: project-level overview + per-file analysis + run metadata. */
public class CodebaseKnowledge {
    public ProjectOverview projectOverview;
    public List<FileAnalysis> files;
    public Map<String, Object> metadata = new LinkedHashMap<>();

    public CodebaseKnowledge() { }

    public CodebaseKnowledge(ProjectOverview projectOverview, List<FileAnalysis> files, Map<String, Object> metadata) {
        this.projectOverview = projectOverview;
        this.files = files;
        this.metadata = metadata;
    }
}
