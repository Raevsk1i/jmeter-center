package com.ltplatform.bitbucket.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltplatform.common.ApiException;
import com.ltplatform.settings.service.SettingsService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BitbucketClient {
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final LocalFixtureSource localFixtures;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public BitbucketClient(SettingsService settings, ObjectMapper mapper, LocalFixtureSource localFixtures) {
        this.settings = settings;
        this.mapper = mapper;
        this.localFixtures = localFixtures;
    }

    public record BitbucketConfig(String mode, String baseUrl, String workspace, String repo, String token, String fixtureRoot) {}

    public BitbucketConfig config() {
        Map<String, Object> bb = settings.getMap("bitbucket");
        String mode = String.valueOf(bb.getOrDefault("mode", "remote"));
        String base = String.valueOf(bb.getOrDefault("baseUrl", "https://api.bitbucket.org/2.0"));
        String workspace = String.valueOf(bb.getOrDefault("workspace", ""));
        String repo = String.valueOf(bb.getOrDefault("repo", ""));
        String token = String.valueOf(bb.getOrDefault("token", ""));
        String fixtureRoot = String.valueOf(bb.getOrDefault("fixtureRoot", "./fixtures/bitbucket"));
        if (!"local".equalsIgnoreCase(mode) && (workspace.isBlank() || repo.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bitbucket settings incomplete");
        }
        return new BitbucketConfig(mode, base, workspace, repo, token, fixtureRoot);
    }

    public List<String> listBranches() {
        BitbucketConfig cfg = config();
        if ("local".equalsIgnoreCase(cfg.mode())) {
            try {
                return localFixtures.listBranches(Path.of(cfg.fixtureRoot()));
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Local fixture error: " + e.getMessage());
            }
        }
        String url = cfg.baseUrl() + "/repositories/" + enc(cfg.workspace()) + "/" + enc(cfg.repo())
                + "/refs/branches?pagelen=100";
        JsonNode root = getJson(url, cfg.token());
        List<String> branches = new ArrayList<>();
        for (JsonNode v : root.path("values")) {
            branches.add(v.path("name").asText());
        }
        return branches;
    }

    public String resolveCommit(String branch) {
        BitbucketConfig cfg = config();
        if ("local".equalsIgnoreCase(cfg.mode())) {
            return localFixtures.resolveCommit(Path.of(cfg.fixtureRoot()).resolve(branch));
        }
        String url = cfg.baseUrl() + "/repositories/" + enc(cfg.workspace()) + "/" + enc(cfg.repo())
                + "/refs/branches/" + enc(branch);
        JsonNode root = getJson(url, cfg.token());
        return root.path("target").path("hash").asText();
    }

    public List<String> listFiles(String commit, String path) {
        BitbucketConfig cfg = config();
        if ("local".equalsIgnoreCase(cfg.mode())) {
            try {
                // commit unused in local mode; branch encoded in path root via systems
                return localFixtures.listFiles(currentBranchRoot(cfg, commit), path);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Local fixture list error: " + e.getMessage());
            }
        }
        String url = cfg.baseUrl() + "/repositories/" + enc(cfg.workspace()) + "/" + enc(cfg.repo())
                + "/src/" + enc(commit) + "/" + path.replace(" ", "%20") + "?pagelen=100";
        JsonNode root = getJson(url, cfg.token());
        List<String> files = new ArrayList<>();
        for (JsonNode v : root.path("values")) {
            String p = v.path("path").asText();
            String type = v.path("type").asText();
            if ("commit_file".equals(type) || "file".equalsIgnoreCase(type)) {
                files.add(p);
            } else if ("commit_directory".equals(type) || "directory".equalsIgnoreCase(type)) {
                files.addAll(listFiles(commit, p));
            }
        }
        return files;
    }

    public byte[] downloadFile(String commit, String path) {
        BitbucketConfig cfg = config();
        if ("local".equalsIgnoreCase(cfg.mode())) {
            try {
                Path file = currentBranchRoot(cfg, commit).resolve(path);
                return java.nio.file.Files.readAllBytes(file);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Local fixture download error: " + e.getMessage());
            }
        }
        String url = cfg.baseUrl() + "/repositories/" + enc(cfg.workspace()) + "/" + enc(cfg.repo())
                + "/src/" + enc(commit) + "/" + path.replace(" ", "%20");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofMinutes(2));
            if (cfg.token() != null && !cfg.token().isBlank()) {
                b.header("Authorization", "Bearer " + cfg.token());
            }
            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Bitbucket download failed: " + resp.statusCode());
            }
            return resp.body();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Bitbucket download error: " + e.getMessage());
        }
    }

    public Map<String, byte[]> downloadTree(String commit, String rootPath) {
        BitbucketConfig cfg = config();
        if ("local".equalsIgnoreCase(cfg.mode())) {
            try {
                return localFixtures.downloadTree(currentBranchRoot(cfg, commit), rootPath);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Local fixture tree error: " + e.getMessage());
            }
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : listFiles(commit, rootPath)) {
            out.put(path, downloadFile(commit, path));
        }
        return out;
    }

    private Path currentBranchRoot(BitbucketConfig cfg, String commit) {
        // Prefer branch matching commit marker; else first branch
        Path root = Path.of(cfg.fixtureRoot());
        try {
            for (String branch : localFixtures.listBranches(root)) {
                Path b = root.resolve(branch);
                if (localFixtures.resolveCommit(b).equals(commit)) {
                    return b;
                }
            }
            List<String> branches = localFixtures.listBranches(root);
            if (!branches.isEmpty()) {
                return root.resolve(branches.getFirst());
            }
        } catch (Exception ignored) {
        }
        return root;
    }

    private JsonNode getJson(String url, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(30));
            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Bitbucket API " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Bitbucket error: " + e.getMessage());
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
