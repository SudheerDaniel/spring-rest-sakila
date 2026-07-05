package com.example.codebaseanalyzer.schema;

import dev.langchain4j.model.output.structured.Description;

import java.util.ArrayList;
import java.util.List;

/** Structured knowledge extracted for a single source file. */
public class FileAnalysis {

    @Description("Path of the file relative to the repository root")
    public String filePath;

    @Description("Java package name, if applicable, otherwise null")
    public String packageName;

    @Description("Top-level class/interface/enum names declared in the file")
    public List<String> classNames = new ArrayList<>();

    @Description("One paragraph summary of this file's purpose/role in the system")
    public String responsibility;

    @Description("The most important methods in the file")
    public List<MethodInfo> keyMethods = new ArrayList<>();

    @Description("One of: Low, Medium, High")
    public String complexity;

    @Description("Brief justification for the complexity rating, and any noteworthy patterns, risks, or design choices")
    public String complexityNotes;

    public FileAnalysis() { }
}
