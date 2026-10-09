package com.ltplatform.orchestration.service;

import com.ltplatform.agent.service.AgentCommandService;
import com.ltplatform.agent.v1.ArtifactFile;
import com.ltplatform.bitbucket.service.BitbucketService;
import com.ltplatform.config.LtPlatformProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ArtifactPackageService {
    private final BitbucketService bitbucket;
    private final LtPlatformProperties props;
    private final JdbcTemplate jdbc;

    public ArtifactPackageService(BitbucketService bitbucket, LtPlatformProperties props, JdbcTemplate jdbc) {
        this.bitbucket = bitbucket;
        this.props = props;
        this.jdbc = jdbc;
    }

    public record PackagedArtifact(String relativePath, String sha256, Path storedPath, byte[] content) {}

    public List<PackagedArtifact> buildPackage(UUID runId, String commitHash, String testRoot) throws Exception {
        Map<String, byte[]> files = bitbucket.downloadTestPackage(commitHash, testRoot);
        Path root = Path.of(props.getArtifactRoot()).resolve(runId.toString());
        Files.createDirectories(root);
        List<PackagedArtifact> packaged = new ArrayList<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String relative = stripRoot(e.getKey(), testRoot);
            Path stored = root.resolve(relative);
            Files.createDirectories(stored.getParent());
            Files.write(stored, e.getValue());
            String sha = HexFormat.of().formatHex(digest.digest(e.getValue()));
            digest.reset();
            jdbc.update(
                    """
                    INSERT INTO execution_artifacts(id, run_id, relative_path, sha256, size_bytes, stored_path, created_at)
                    VALUES (?,?,?,?,?,?,NOW())
                    """,
                    UUID.randomUUID(), runId, relative, sha, (long) e.getValue().length, stored.toString()
            );
            packaged.add(new PackagedArtifact(relative, sha, stored, e.getValue()));
        }
        return packaged;
    }

    public List<ArtifactFile> toProto(List<PackagedArtifact> artifacts) {
        return artifacts.stream()
                .map(a -> AgentCommandService.artifact(a.relativePath(), a.sha256(), a.content()))
                .toList();
    }

    private static String stripRoot(String path, String root) {
        String normalized = path.replace('\\', '/');
        String prefix = root.endsWith("/") ? root : root + "/";
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }
        if (normalized.equals(root)) {
            return "test.jmx";
        }
        int idx = normalized.indexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }
}
