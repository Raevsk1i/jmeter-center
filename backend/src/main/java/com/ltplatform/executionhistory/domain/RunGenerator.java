package com.ltplatform.executionhistory.domain;

import com.ltplatform.reservation.domain.GeneratorRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "run_generators")
public class RunGenerator {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "generator_id", nullable = false)
    private UUID generatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeneratorRole role;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID getGeneratorId() { return generatorId; }
    public void setGeneratorId(UUID generatorId) { this.generatorId = generatorId; }
    public GeneratorRole getRole() { return role; }
    public void setRole(GeneratorRole role) { this.role = role; }
}
