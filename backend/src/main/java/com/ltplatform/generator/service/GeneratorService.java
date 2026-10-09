package com.ltplatform.generator.service;

import com.ltplatform.common.ApiException;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.generator.dto.GeneratorDtos.CreateCredentialRequest;
import com.ltplatform.generator.dto.GeneratorDtos.CreateGeneratorRequest;
import com.ltplatform.generator.dto.GeneratorDtos.CredentialResponse;
import com.ltplatform.generator.dto.GeneratorDtos.GeneratorResponse;
import com.ltplatform.generator.dto.GeneratorDtos.UpdateGeneratorRequest;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.generator.repo.SshCredentialRepository;
import com.ltplatform.provisioning.service.ProvisioningService;
import com.ltplatform.provisioning.ssh.SshAgentTunnelService;
import com.ltplatform.provisioning.ssh.SshClientFactory;
import com.ltplatform.security.audit.AuditService;
import com.ltplatform.security.crypto.SecretBox;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class GeneratorService {
    private final GeneratorRepository generators;
    private final SshCredentialRepository credentials;
    private final SecretBox secretBox;
    private final ProvisioningService provisioning;
    private final AuditService audit;
    private final SshAgentTunnelService tunnels;

    public GeneratorService(
            GeneratorRepository generators,
            SshCredentialRepository credentials,
            SecretBox secretBox,
            ProvisioningService provisioning,
            AuditService audit,
            SshAgentTunnelService tunnels
    ) {
        this.generators = generators;
        this.credentials = credentials;
        this.secretBox = secretBox;
        this.provisioning = provisioning;
        this.audit = audit;
        this.tunnels = tunnels;
    }

    @Transactional
    public CredentialResponse createCredential(CreateCredentialRequest req, String actor) {
        SshCredential cred = new SshCredential();
        cred.setId(UUID.randomUUID());
        cred.setName(req.name());
        cred.setPrivateKeyEnc(secretBox.encryptString(SshClientFactory.normalizePem(req.privateKeyPem())));
        if (req.passphrase() != null && !req.passphrase().isBlank()) {
            cred.setPassphraseEnc(secretBox.encryptString(req.passphrase()));
        }
        credentials.save(cred);
        audit.record(actor, "SSH_CREDENTIAL_CREATE", "SshCredential", cred.getId().toString(), Map.of("name", req.name()));
        return new CredentialResponse(cred.getId(), cred.getName(), cred.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<CredentialResponse> listCredentials() {
        return credentials.findAll().stream()
                .map(c -> new CredentialResponse(c.getId(), c.getName(), c.getCreatedAt()))
                .toList();
    }

    @Transactional
    public GeneratorResponse create(CreateGeneratorRequest req, String actor) {
        if (!credentials.existsById(req.sshCredentialId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SSH credential not found");
        }
        Generator g = new Generator();
        g.setId(UUID.randomUUID());
        g.setName(req.name());
        g.setHostname(req.hostname());
        g.setSshPort(req.sshPort() != null ? req.sshPort() : 22);
        g.setSshUser(req.sshUser() != null && !req.sshUser().isBlank() ? req.sshUser() : "root");
        g.setSshCredentialId(req.sshCredentialId());
        g.setStatus(GeneratorStatus.PREPARING);
        generators.save(g);
        audit.record(actor, "GENERATOR_CREATE", "Generator", g.getId().toString(), Map.of("hostname", g.getHostname()));
        if (req.provisionNow()) {
            scheduleProvisionAfterCommit(g.getId());
        }
        return toResponse(g);
    }

    @Transactional(readOnly = true)
    public List<GeneratorResponse> list() {
        return generators.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public GeneratorResponse get(UUID id) {
        return toResponse(require(id));
    }

    @Transactional
    public GeneratorResponse update(UUID id, UpdateGeneratorRequest req, String actor) {
        Generator g = require(id);
        if (req.name() != null) g.setName(req.name());
        if (req.hostname() != null) g.setHostname(req.hostname());
        if (req.sshPort() != null) g.setSshPort(req.sshPort());
        if (req.sshUser() != null) g.setSshUser(req.sshUser());
        if (req.sshCredentialId() != null) g.setSshCredentialId(req.sshCredentialId());
        g.touch();
        generators.save(g);
        audit.record(actor, "GENERATOR_UPDATE", "Generator", id.toString(), Map.of());
        return toResponse(g);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        Generator g = require(id);
        if (g.getStatus() == GeneratorStatus.RUNNING || g.getStatus() == GeneratorStatus.RESERVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot delete reserved/running generator");
        }
        tunnels.close(id);
        generators.delete(g);
        audit.record(actor, "GENERATOR_DELETE", "Generator", id.toString(), Map.of());
    }

    @Transactional
    public void reprovision(UUID id, String actor) {
        Generator g = require(id);
        g.setStatus(GeneratorStatus.PREPARING);
        g.setProvisionError(null);
        g.touch();
        generators.save(g);
        audit.record(actor, "GENERATOR_REPROVISION", "Generator", id.toString(), Map.of());
        scheduleProvisionAfterCommit(id);
    }

    /**
     * Provisioning runs on another thread; start it only after the creating transaction commits
     * so the async worker can see the new generator row.
     */
    private void scheduleProvisionAfterCommit(UUID generatorId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    provisioning.provisionAsync(generatorId);
                }
            });
        } else {
            provisioning.provisionAsync(generatorId);
        }
    }

    @Transactional
    public void updateHeartbeat(UUID generatorId, double cpu, long ramUsed, long ramTotal, long diskFree,
                                String agentVersion, String javaVersion, String jmeterVersion) {
        Generator g = require(generatorId);
        g.setCpuUsagePercent(cpu);
        g.setRamMb(ramTotal);
        g.setDiskFreeMb(diskFree);
        g.setLastHeartbeatAt(Instant.now());
        if (agentVersion != null) g.setAgentVersion(agentVersion);
        if (javaVersion != null) g.setJavaVersion(javaVersion);
        if (jmeterVersion != null) g.setJmeterVersion(jmeterVersion);
        if (g.getStatus() == GeneratorStatus.PREPARING) {
            g.setStatus(GeneratorStatus.AVAILABLE);
            g.setProvisionStep("READY");
        }
        // OFFLINE reconcile is performed by HeartbeatMonitor/orchestrator; heartbeat alone does not release or flip busy hosts
        g.touch();
        generators.save(g);
    }

    @Transactional
    public void markOffline(UUID id) {
        Generator g = require(id);
        if (g.getStatus() == GeneratorStatus.AVAILABLE
                || g.getStatus() == GeneratorStatus.RESERVED
                || g.getStatus() == GeneratorStatus.RUNNING) {
            g.setStatus(GeneratorStatus.OFFLINE);
            g.touch();
            generators.save(g);
        }
    }

    public Generator require(UUID id) {
        return generators.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Generator not found: " + id));
    }

    public GeneratorResponse toResponse(Generator g) {
        return new GeneratorResponse(
                g.getId(), g.getName(), g.getHostname(), g.getSshPort(), g.getSshUser(),
                g.getSshCredentialId(), g.getStatus(), g.getAgentVersion(), g.getJavaVersion(),
                g.getJmeterVersion(), g.getCpuCores(), g.getRamMb(), g.getDiskFreeMb(),
                g.getCpuUsagePercent(), g.getLastHeartbeatAt(), g.getFencingToken(),
                g.getProvisionStep(), g.getProvisionError(), g.getCreatedAt(), g.getUpdatedAt()
        );
    }
}
