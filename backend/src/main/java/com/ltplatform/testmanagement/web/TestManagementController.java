package com.ltplatform.testmanagement.web;

import com.ltplatform.testmanagement.domain.TestType;
import com.ltplatform.testmanagement.service.TestManagementService;
import com.ltplatform.testmanagement.service.TestManagementService.GroupDto;
import com.ltplatform.testmanagement.service.TestManagementService.SystemDto;
import com.ltplatform.testmanagement.service.TestManagementService.TestDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TestManagementController {
    private final TestManagementService service;

    public TestManagementController(TestManagementService service) {
        this.service = service;
    }

    public record CreateSystemRequest(@NotBlank String name, @NotBlank String bitbucketBranch, String description) {}
    public record CreateGroupRequest(@NotBlank String name, UUID parentId) {}
    public record CreateTestRequest(
            UUID groupId,
            @NotNull UUID systemId,
            @NotBlank String name,
            @NotBlank String jmxPath,
            TestType testType,
            Map<String, String> defaultProperties,
            String description
    ) {}

    @GetMapping("/systems")
    public List<SystemDto> systems() { return service.listSystems(); }

    @PostMapping("/systems")
    public SystemDto createSystem(@RequestBody CreateSystemRequest req) {
        return service.upsertSystem(req.name(), req.bitbucketBranch(), req.description());
    }

    @GetMapping("/test-groups")
    public List<GroupDto> groups() { return service.listGroups(); }

    @PostMapping("/test-groups")
    public GroupDto createGroup(@RequestBody CreateGroupRequest req) {
        return service.createGroup(req.name(), req.parentId());
    }

    @GetMapping("/tests")
    public List<TestDto> tests() { return service.listTests(); }

    @PostMapping("/tests")
    public TestDto createTest(@RequestBody CreateTestRequest req) {
        return service.createTest(req.groupId(), req.systemId(), req.name(), req.jmxPath(),
                req.testType(), req.defaultProperties(), req.description());
    }
}
