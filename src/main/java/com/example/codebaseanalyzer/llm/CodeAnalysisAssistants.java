package com.example.codebaseanalyzer.llm;

import com.example.codebaseanalyzer.schema.ChunkAnalysis;
import com.example.codebaseanalyzer.schema.ProjectOverview;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j "AI Service" interfaces. Each method's return type (a plain POJO annotated
 * with {@code @Description} fields, see the schema package) tells LangChain4j to request
 * and parse a structured, schema-conforming response from the model -- this is the Java
 * equivalent of the Python version's `chat_model.with_structured_output(PydanticModel)`.
 */
public interface CodeAnalysisAssistants {

    interface ChunkExtractionAssistant {
        @SystemMessage("""
            You are a senior software engineer performing static code analysis.
            You will be given the contents of one or more source files (a "chunk") from \
            a single codebase. For EACH file present in the chunk, extract:
              - its package name (if any)
              - the top-level class/interface/enum name(s) it declares
              - a one-paragraph description of the file's responsibility
              - its most important methods, with full signatures and short descriptions
              - a complexity rating (Low, Medium, or High) with a short justification, \
            noting any noteworthy design patterns, risks, or code smells

            If a file appears only partially (it was split due to size), analyze the \
            portion you can see and note in your response that it is a partial view.

            Respond ONLY with the structured data requested -- no extra commentary.
            """)
        ChunkAnalysis analyze(@UserMessage String chunkText);
    }

    interface OverviewSynthesisAssistant {
        @SystemMessage("""
            You are a senior software architect. You will be given a list of short, \
            per-file summaries that were extracted from a codebase. Using ONLY this \
            information, produce a high-level project overview: overall purpose, \
            architecture/layering summary, notable modules, and key technologies used. \
            Respond ONLY with the structured data requested.
            """)
        ProjectOverview synthesize(@UserMessage String summariesText);
    }
}
