package com.ltplatform.bitbucket.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class LocalFixtureSource {
    public List<String> listBranches(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    public String resolveCommit(Path branchDir) {
        Path commitFile = branchDir.resolve(".commit");
        if (Files.isRegularFile(commitFile)) {
            try {
                return Files.readString(commitFile).trim();
            } catch (IOException ignored) {
            }
        }
        return "local-" + Integer.toHexString(branchDir.toString().hashCode());
    }

    public Map<String, byte[]> downloadTree(Path root, String relativeRoot) throws IOException {
        Path base = root.resolve(relativeRoot);
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!Files.exists(base)) return out;
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.put(rel, Files.readAllBytes(p));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return out;
    }

    public List<String> listFiles(Path root, String relativeRoot) throws IOException {
        return new ArrayList<>(downloadTree(root, relativeRoot).keySet());
    }
}
