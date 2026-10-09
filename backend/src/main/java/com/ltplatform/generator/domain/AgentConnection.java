package com.ltplatform.generator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_connections")
public class AgentConnection {
    @Id
    private UUID id;

    @Column(name = "generator_id", nullable = false, unique = true)
    private UUID generatorId;

    @Column(name = "agent_id", nullable = false, unique = true)
    private String agentId;

    @Column(name = "cert_fingerprint")
    private String certFingerprint;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGeneratorId() { return generatorId; }
    public void setGeneratorId(UUID generatorId) { this.generatorId = generatorId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getCertFingerprint() { return certFingerprint; }
    public void setCertFingerprint(String certFingerprint) { this.certFingerprint = certFingerprint; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
