package com.ltplatform.generator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name = "generators")
public class Generator {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String hostname;

    @Column(name = "ssh_port", nullable = false)
    private int sshPort = 22;

    @Column(name = "ssh_user", nullable = false)
    private String sshUser = "root";

    @Column(name = "ssh_credential_id")
    private UUID sshCredentialId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeneratorStatus status = GeneratorStatus.PREPARING;

    @Column(name = "agent_version", length = 255)
    private String agentVersion;

    @Column(name = "java_version", length = 512)
    private String javaVersion;

    @Column(name = "jmeter_version", length = 512)
    private String jmeterVersion;

    @Column(name = "cpu_cores")
    private Integer cpuCores;

    @Column(name = "ram_mb")
    private Long ramMb;

    @Column(name = "disk_free_mb")
    private Long diskFreeMb;

    @Column(name = "cpu_usage_percent")
    private Double cpuUsagePercent;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "provision_step")
    private String provisionStep;

    @Column(name = "provision_error")
    private String provisionError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }
    public UUID getSshCredentialId() { return sshCredentialId; }
    public void setSshCredentialId(UUID sshCredentialId) { this.sshCredentialId = sshCredentialId; }
    public GeneratorStatus getStatus() { return status; }
    public void setStatus(GeneratorStatus status) { this.status = status; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = clip(agentVersion, 255); }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = clip(javaVersion, 512); }
    public String getJmeterVersion() { return jmeterVersion; }
    public void setJmeterVersion(String jmeterVersion) { this.jmeterVersion = clip(jmeterVersion, 512); }

    private static String clip(String value, int max) {
        if (value == null) return null;
        String trimmed = value.replace('\r', ' ').replace('\n', ' ').trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
    public Integer getCpuCores() { return cpuCores; }
    public void setCpuCores(Integer cpuCores) { this.cpuCores = cpuCores; }
    public Long getRamMb() { return ramMb; }
    public void setRamMb(Long ramMb) { this.ramMb = ramMb; }
    public Long getDiskFreeMb() { return diskFreeMb; }
    public void setDiskFreeMb(Long diskFreeMb) { this.diskFreeMb = diskFreeMb; }
    public Double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(Double cpuUsagePercent) { this.cpuUsagePercent = cpuUsagePercent; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long fencingToken) { this.fencingToken = fencingToken; }
    public String getProvisionStep() { return provisionStep; }
    public void setProvisionStep(String provisionStep) { this.provisionStep = provisionStep; }
    public String getProvisionError() { return provisionError; }
    public void setProvisionError(String provisionError) { this.provisionError = provisionError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
