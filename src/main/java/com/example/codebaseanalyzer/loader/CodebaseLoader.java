package com.example.codebaseanalyzer.loader;

import com.example.codebaseanalyzer.model.SourceFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Reads a codebase from disk into a list of {@link SourceFile}s.
 * Kept dependency-free and unit-testable without any LLM/network involvement,
 * mirroring the same design used for the Python version of this tool.
 */
public class CodebaseLoader {

    public static final Set<String> DEFAULT_INCLUDE_EXTENSIONS = Set.of(".java", ".kt");

    public static final Set<String> DEFAULT_EXCLUDE_DIRS = Set.of(
            ".git", ".gradle", "build", "out", "target",
            "node_modules", ".idea", ".vscode", "bin"
    );

    /** Walk {@code repoPath} and return every matching source file, sorted by path for reproducibility. */
    public List<SourceFile> loadSourceFiles(String repoPath) {
        return loadSourceFiles(repoPath, DEFAULT_INCLUDE_EXTENSIONS, DEFAULT_EXCLUDE_DIRS);
    }

    public List<SourceFile> loadSourceFiles(String repoPath, Set<String> includeExtensions, Set<String> excludeDirs) {
        Path root = Paths.get(repoPath).toAbsolutePath().normalize();
        List<SourceFile> results = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(root) && (excludeDirs.contains(name) || name.startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    int dot = fileName.lastIndexOf('.');
                    String ext = dot >= 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : "";
                    if (includeExtensions.contains(ext)) {
                        try {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            String relPath = root.relativize(file).toString().replace('\\', '/');
                            results.add(new SourceFile(relPath, content));
                        } catch (IOException e) {
                            // Skip unreadable files (e.g. binary misdetected, permission issues) rather than
                            // failing the whole run.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk repo path: " + repoPath, e);
        }

        results.sort(Comparator.comparing(SourceFile::getPath));
        return results;
    }
}
