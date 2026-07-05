package com.example.codebaseanalyzer.schema;

import dev.langchain4j.model.output.structured.Description;

/** One extracted method: name, full signature, and a short description. */
public class MethodInfo {

    @Description("Method name")
    public String name;

    @Description("Full method signature, e.g. 'public List<Film> findAll(int page)'")
    public String signature;

    @Description("One or two sentence description of what the method does")
    public String description;

    public MethodInfo() { }

    public MethodInfo(String name, String signature, String description) {
        this.name = name;
        this.signature = signature;
        this.description = description;
    }
}
