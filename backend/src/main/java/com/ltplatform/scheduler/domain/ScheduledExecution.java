package com.ltplatform.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "scheduled_executions")
public class ScheduledExecution {
    @Id
    private UUID id;

    @Column(name = "test_definition_id", nullable = false)
    private UUID testDefinitionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String configuration = "{}";

    @Column(name = "fire_at", nullable = false)
    private Instant fireAt;

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "quartz_job_key")
    private String quartzJobKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTestDefinitionId() { return testDefinitionId; }
    public void setTestDefinitionId(UUID testDefinitionId) { this.testDefinitionId = testDefinitionId; }
    public String getConfiguration() { return configuration; }
    public void setConfiguration(String configuration) { this.configuration = configuration; }
    public Instant getFireAt() { return fireAt; }
    public void setFireAt(Instant fireAt) { this.fireAt = fireAt; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getQuartzJobKey() { return quartzJobKey; }
    public void setQuartzJobKey(String quartzJobKey) { this.quartzJobKey = quartzJobKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
