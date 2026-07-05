package com.example.codebaseanalyzer;

import com.example.codebaseanalyzer.chunker.CodebaseChunker;
import com.example.codebaseanalyzer.chunker.TokenCounter;
import com.example.codebaseanalyzer.loader.CodebaseLoader;
import com.example.codebaseanalyzer.model.Chunk;
import com.example.codebaseanalyzer.model.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodebasePipelineTest {

    private void write(Path base, String relPath, String content) throws IOException {
        Path file = base.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void loaderFindsJavaFilesAndSkipsBuildDirs(@TempDir Path tmp) throws IOException {
        write(tmp, "src/main/java/Foo.java", "public class Foo {}");
        write(tmp, "build/generated/Ignored.java", "public class Ignored {}");
        write(tmp, "README.md", "not source");

        List<SourceFile> files = new CodebaseLoader().loadSourceFiles(tmp.toString());
        List<String> paths = files.stream().map(SourceFile::getPath).toList();

        assertTrue(paths.contains("src/main/java/Foo.java"));
        assertTrue(paths.stream().noneMatch(p -> p.contains("build")));
        assertTrue(paths.stream().noneMatch(p -> p.endsWith(".md")));
    }

    @Test
    void chunkerPacksSmallFilesTogether(@TempDir Path tmp) throws IOException {
        for (int i = 0; i < 5; i++) {
            write(tmp, "src/File" + i + ".java", "public class File" + i + " { void m() {} }");
        }
        List<SourceFile> files = new CodebaseLoader().loadSourceFiles(tmp.toString());
        List<Chunk> chunks = new CodebaseChunker().buildChunks(files, 6000);

        assertEquals(1, chunks.size());
        assertEquals(5, chunks.get(0).getFilePaths().size());
    }

    @Test
    void chunkerSplitsOversizedFile(@TempDir Path tmp) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            body.append("        int x").append(i).append(" = ").append(i).append(";\n");
        }
        String content = "public class Big {\n    void m() {\n" + body + "    }\n}";
        write(tmp, "src/Big.java", content);

        List<SourceFile> files = new CodebaseLoader().loadSourceFiles(tmp.toString());
        assertTrue(TokenCounter.count(files.get(0).getContent()) > 500);

        List<Chunk> chunks = new CodebaseChunker().buildChunks(files, 500);
        assertTrue(chunks.size() >= 2);
        for (Chunk c : chunks) {
            assertTrue(c.getTokenCount() <= 500 * 1.05);
        }
    }

    @Test
    void chunkerRespectsTokenBudgetAcrossManyFiles(@TempDir Path tmp) throws IOException {
        for (int i = 0; i < 50; i++) {
            write(tmp, "src/F" + i + ".java", "public class C {\n" + "    int a;\n".repeat(50) + "}");
        }
        List<SourceFile> files = new CodebaseLoader().loadSourceFiles(tmp.toString());
        List<Chunk> chunks = new CodebaseChunker().buildChunks(files, 1000);

        assertTrue(chunks.size() > 1);
        for (Chunk c : chunks) {
            assertTrue(c.getTokenCount() <= 1000 * 1.05);
        }
    }
}
