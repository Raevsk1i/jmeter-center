package com.ltplatform.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "generator_reservations")
public class GeneratorReservation {
    @Id
    private UUID id;

    @Column(name = "generator_id", nullable = false)
    private UUID generatorId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeneratorRole role;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGeneratorId() { return generatorId; }
    public void setGeneratorId(UUID generatorId) { this.generatorId = generatorId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public GeneratorRole getRole() { return role; }
    public void setRole(GeneratorRole role) { this.role = role; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long fencingToken) { this.fencingToken = fencingToken; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
