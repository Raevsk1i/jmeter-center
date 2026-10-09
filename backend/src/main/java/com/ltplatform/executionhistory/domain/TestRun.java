package com.ltplatform.executionhistory.domain;

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
@Table(name = "test_runs")
public class TestRun {
    @Id
    private UUID id;

    @Column(name = "test_definition_id")
    private UUID testDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestRunStatus status = TestRunStatus.SCHEDULED;

    @Column(name = "commit_hash")
    private String commitHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String configuration = "{}";

    @Column(name = "master_generator_id")
    private UUID masterGeneratorId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTestDefinitionId() { return testDefinitionId; }
    public void setTestDefinitionId(UUID testDefinitionId) { this.testDefinitionId = testDefinitionId; }
    public TestRunStatus getStatus() { return status; }
    public void setStatus(TestRunStatus status) { this.status = status; }
    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }
    public String getConfiguration() { return configuration; }
    public void setConfiguration(String configuration) { this.configuration = configuration; }
    public UUID getMasterGeneratorId() { return masterGeneratorId; }
    public void setMasterGeneratorId(UUID masterGeneratorId) { this.masterGeneratorId = masterGeneratorId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void touch() { this.updatedAt = Instant.now(); }
}
