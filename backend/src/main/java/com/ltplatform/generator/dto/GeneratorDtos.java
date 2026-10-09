package com.ltplatform.generator.dto;

import com.ltplatform.generator.domain.GeneratorStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class GeneratorDtos {
    private GeneratorDtos() {}

    public record CreateCredentialRequest(
            @NotBlank String name,
            @NotBlank String privateKeyPem,
            String passphrase
    ) {}

    public record CredentialResponse(UUID id, String name, Instant createdAt) {}

    public record CreateGeneratorRequest(
            @NotBlank String name,
            @NotBlank String hostname,
            Integer sshPort,
            String sshUser,
            @NotNull UUID sshCredentialId,
            boolean provisionNow
    ) {}

    public record UpdateGeneratorRequest(
            String name,
            String hostname,
            Integer sshPort,
            String sshUser,
            UUID sshCredentialId
    ) {}

    public record GeneratorResponse(
            UUID id,
            String name,
            String hostname,
            int sshPort,
            String sshUser,
            UUID sshCredentialId,
            GeneratorStatus status,
            String agentVersion,
            String javaVersion,
            String jmeterVersion,
            Integer cpuCores,
            Long ramMb,
            Long diskFreeMb,
            Double cpuUsagePercent,
            Instant lastHeartbeatAt,
            long fencingToken,
            String provisionStep,
            String provisionError,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ProvisionStepResponse(
            String stepName,
            String status,
            String message,
            Instant startedAt,
            Instant finishedAt
    ) {}
}
