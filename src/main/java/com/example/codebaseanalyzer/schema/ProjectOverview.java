package com.example.codebaseanalyzer.schema;

import dev.langchain4j.model.output.structured.Description;

import java.util.ArrayList;
import java.util.List;

/** High-level, project-wide summary synthesized from all per-file analyses. */
public class ProjectOverview {

    @Description("High-level description of what the project does and why it exists")
    public String purpose;

    @Description("Summary of the overall architecture / layering / key design patterns observed")
    public String architectureSummary;

    @Description("Notable modules/packages and their responsibilities, in short form")
    public List<String> keyModules = new ArrayList<>();

    @Description("Key frameworks/libraries the project relies on")
    public List<String> notableTechnologies = new ArrayList<>();

    public ProjectOverview() { }
}
