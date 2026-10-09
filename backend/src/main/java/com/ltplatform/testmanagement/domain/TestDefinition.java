package com.ltplatform.testmanagement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "test_definitions")
public class TestDefinition {
    @Id
    private UUID id;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "system_id", nullable = false)
    private UUID systemId;

    @Column(nullable = false)
    private String name;

    @Column(name = "jmx_path", nullable = false)
    private String jmxPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "test_type", nullable = false)
    private TestType testType = TestType.PERF;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_properties", nullable = false, columnDefinition = "jsonb")
    private String defaultProperties = "{}";

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public UUID getSystemId() { return systemId; }
    public void setSystemId(UUID systemId) { this.systemId = systemId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getJmxPath() { return jmxPath; }
    public void setJmxPath(String jmxPath) { this.jmxPath = jmxPath; }
    public TestType getTestType() { return testType; }
    public void setTestType(TestType testType) { this.testType = testType; }
    public String getDefaultProperties() { return defaultProperties; }
    public void setDefaultProperties(String defaultProperties) { this.defaultProperties = defaultProperties; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
