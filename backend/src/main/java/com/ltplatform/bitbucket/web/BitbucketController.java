package com.ltplatform.bitbucket.web;

import com.ltplatform.bitbucket.service.BitbucketService;
import com.ltplatform.bitbucket.service.BitbucketService.BranchInfo;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bitbucket")
public class BitbucketController {
    private final BitbucketService bitbucket;

    public BitbucketController(BitbucketService bitbucket) {
        this.bitbucket = bitbucket;
    }

    @GetMapping("/branches")
    public List<String> branches() {
        return bitbucket.listBranches();
    }

    @PostMapping("/sync")
    public List<BranchInfo> sync() {
        return bitbucket.syncSystems();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        try {
            List<String> branches = bitbucket.listBranches();
            return Map.of("ok", true, "branchCount", branches.size());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
