package com.ltplatform.bitbucket.service;

import com.ltplatform.bitbucket.client.BitbucketClient;
import com.ltplatform.testmanagement.service.TestManagementService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BitbucketService {
    private final BitbucketClient client;
    private final TestManagementService tests;

    public BitbucketService(BitbucketClient client, TestManagementService tests) {
        this.client = client;
        this.tests = tests;
    }

    public record BranchInfo(String branch, String systemName, List<String> testRoots) {}

    public List<BranchInfo> syncSystems() {
        List<BranchInfo> result = new ArrayList<>();
        for (String branch : client.listBranches()) {
            tests.upsertSystem(branch, branch, "Synced from Bitbucket branch");
            List<String> roots = new ArrayList<>();
            try {
                String commit = client.resolveCommit(branch);
                for (String candidate : List.of("perf_test", "stability_test")) {
                    try {
                        List<String> files = client.listFiles(commit, candidate);
                        if (!files.isEmpty()) roots.add(candidate);
                    } catch (Exception ignored) {
                        // folder may not exist
                    }
                }
            } catch (Exception ignored) {
            }
            result.add(new BranchInfo(branch, branch, roots));
        }
        return result;
    }

    public List<String> listBranches() {
        return client.listBranches();
    }

    public String resolveCommit(String branch) {
        return client.resolveCommit(branch);
    }

    public Map<String, byte[]> downloadTestPackage(String commit, String testRoot) {
        return client.downloadTree(commit, testRoot);
    }
}
