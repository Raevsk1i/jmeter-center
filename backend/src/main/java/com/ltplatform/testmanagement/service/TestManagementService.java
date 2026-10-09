package com.ltplatform.testmanagement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltplatform.common.ApiException;
import com.ltplatform.testmanagement.domain.SystemEntity;
import com.ltplatform.testmanagement.domain.TestDefinition;
import com.ltplatform.testmanagement.domain.TestGroup;
import com.ltplatform.testmanagement.domain.TestType;
import com.ltplatform.testmanagement.repo.SystemRepository;
import com.ltplatform.testmanagement.repo.TestDefinitionRepository;
import com.ltplatform.testmanagement.repo.TestGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestManagementService {
    private final SystemRepository systems;
    private final TestGroupRepository groups;
    private final TestDefinitionRepository tests;
    private final ObjectMapper mapper;

    public TestManagementService(
            SystemRepository systems,
            TestGroupRepository groups,
            TestDefinitionRepository tests,
            ObjectMapper mapper
    ) {
        this.systems = systems;
        this.groups = groups;
        this.tests = tests;
        this.mapper = mapper;
    }

    public record SystemDto(UUID id, String name, String bitbucketBranch, String description) {}
    public record GroupDto(UUID id, String name, UUID parentId) {}
    public record TestDto(UUID id, UUID groupId, UUID systemId, String name, String jmxPath,
                          TestType testType, Map<String, String> defaultProperties, String description) {}

    @Transactional
    public SystemDto upsertSystem(String name, String branch, String description) {
        SystemEntity s = systems.findByBitbucketBranch(branch).orElseGet(SystemEntity::new);
        if (s.getId() == null) {
            s.setId(UUID.randomUUID());
            s.setCreatedAt(Instant.now());
        }
        s.setName(name);
        s.setBitbucketBranch(branch);
        s.setDescription(description);
        s.setUpdatedAt(Instant.now());
        systems.save(s);
        return new SystemDto(s.getId(), s.getName(), s.getBitbucketBranch(), s.getDescription());
    }

    @Transactional(readOnly = true)
    public List<SystemDto> listSystems() {
        return systems.findAll().stream()
                .map(s -> new SystemDto(s.getId(), s.getName(), s.getBitbucketBranch(), s.getDescription()))
                .toList();
    }

    @Transactional
    public GroupDto createGroup(String name, UUID parentId) {
        TestGroup g = new TestGroup();
        g.setId(UUID.randomUUID());
        g.setName(name);
        g.setParentId(parentId);
        groups.save(g);
        return new GroupDto(g.getId(), g.getName(), g.getParentId());
    }

    @Transactional(readOnly = true)
    public List<GroupDto> listGroups() {
        return groups.findAll().stream()
                .map(g -> new GroupDto(g.getId(), g.getName(), g.getParentId()))
                .toList();
    }

    @Transactional
    public TestDto createTest(UUID groupId, UUID systemId, String name, String jmxPath,
                              TestType type, Map<String, String> properties, String description) {
        if (!systems.existsById(systemId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "System not found");
        }
        TestDefinition t = new TestDefinition();
        t.setId(UUID.randomUUID());
        t.setGroupId(groupId);
        t.setSystemId(systemId);
        t.setName(name);
        t.setJmxPath(jmxPath);
        t.setTestType(type != null ? type : TestType.PERF);
        try {
            t.setDefaultProperties(mapper.writeValueAsString(properties != null ? properties : Map.of()));
        } catch (Exception e) {
            t.setDefaultProperties("{}");
        }
        t.setDescription(description);
        tests.save(t);
        return toDto(t);
    }

    @Transactional(readOnly = true)
    public List<TestDto> listTests() {
        return tests.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public TestDefinition requireTest(UUID id) {
        return tests.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Test not found"));
    }

    @Transactional(readOnly = true)
    public SystemEntity requireSystem(UUID id) {
        return systems.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "System not found"));
    }

    private TestDto toDto(TestDefinition t) {
        Map<String, String> props;
        try {
            props = mapper.readValue(t.getDefaultProperties(),
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        } catch (Exception e) {
            props = Map.of();
        }
        return new TestDto(t.getId(), t.getGroupId(), t.getSystemId(), t.getName(), t.getJmxPath(),
                t.getTestType(), props, t.getDescription());
    }
}
