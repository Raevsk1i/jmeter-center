package com.ltplatform.agent.grpc;

import com.ltplatform.agent.session.AgentSessionRegistry;
import com.ltplatform.agent.session.AgentSessionRegistry.CommandOutcome;
import com.ltplatform.agent.v1.AgentControlGrpc;
import com.ltplatform.agent.v1.AgentMessage;
import com.ltplatform.agent.v1.CommandResult;
import com.ltplatform.agent.v1.ControllerMessage;
import com.ltplatform.agent.v1.Heartbeat;
import com.ltplatform.agent.v1.LogChunk;
import com.ltplatform.agent.v1.RegisterRequest;
import com.ltplatform.agent.v1.RegisterResponse;
import com.ltplatform.generator.domain.AgentConnection;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.repo.AgentConnectionRepository;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.generator.service.GeneratorService;
import com.ltplatform.generator.service.ReconnectReconcileService;
import com.ltplatform.logging.service.LogStorageService;
import com.ltplatform.security.crypto.CertificateAuthority;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentControlGrpcService extends AgentControlGrpc.AgentControlImplBase {
    private static final Logger log = LoggerFactory.getLogger(AgentControlGrpcService.class);

    private final AgentSessionRegistry sessions;
    private final GeneratorRepository generators;
    private final AgentConnectionRepository connections;
    private final GeneratorService generatorService;
    private final ReconnectReconcileService reconcile;
    private final CertificateAuthority ca;
    private final LogStorageService logs;

    public AgentControlGrpcService(
            AgentSessionRegistry sessions,
            GeneratorRepository generators,
            AgentConnectionRepository connections,
            GeneratorService generatorService,
            ReconnectReconcileService reconcile,
            CertificateAuthority ca,
            LogStorageService logs
    ) {
        this.sessions = sessions;
        this.generators = generators;
        this.connections = connections;
        this.generatorService = generatorService;
        this.reconcile = reconcile;
        this.ca = ca;
        this.logs = logs;
    }

    @Override
    public StreamObserver<AgentMessage> agentSession(StreamObserver<ControllerMessage> responseObserver) {
        return new StreamObserver<>() {
            private UUID generatorId;
            private String sessionId;
            private String agentId;

            @Override
            public void onNext(AgentMessage msg) {
                try {
                    switch (msg.getPayloadCase()) {
                        case REGISTER -> handleRegister(msg.getRegister(), responseObserver);
                        case HEARTBEAT -> handleHeartbeat(msg.getHeartbeat());
                        case COMMAND_RESULT -> handleResult(msg.getCommandResult());
                        case LOG_CHUNK -> handleLog(msg.getLogChunk());
                        case EXECUTION_STATUS -> log.debug("Execution status: {}", msg.getExecutionStatus());
                        case METRICS -> log.debug("Metrics: {}", msg.getMetrics());
                        default -> log.warn("Unknown agent payload");
                    }
                } catch (Exception e) {
                    log.error("Error handling agent message: {}", e.getMessage(), e);
                }
            }

            private void handleRegister(RegisterRequest req, StreamObserver<ControllerMessage> out) {
                agentId = req.getAgentId();
                UUID genId = UUID.fromString(req.getGeneratorId());
                Generator generator = generators.findById(genId).orElse(null);
                if (generator == null) {
                    out.onNext(ControllerMessage.newBuilder()
                            .setRegisterResponse(RegisterResponse.newBuilder()
                                    .setAccepted(false)
                                    .setMessage("Unknown generator")
                                    .build())
                            .build());
                    return;
                }
                sessionId = UUID.randomUUID().toString();
                generatorId = genId;
                var issued = ca.issueClientCert(agentId);
                AgentConnection conn = connections.findByGeneratorId(genId).orElseGet(AgentConnection::new);
                if (conn.getId() == null) {
                    conn.setId(UUID.randomUUID());
                    conn.setGeneratorId(genId);
                    conn.setAgentId(agentId);
                    conn.setRegisteredAt(Instant.now());
                }
                conn.setCertFingerprint(issued.fingerprint());
                conn.setSessionId(sessionId);
                conn.setLastSeenAt(Instant.now());
                connections.save(conn);

                sessions.register(genId, agentId, sessionId, out);
                generator.setAgentVersion(req.getAgentVersion());
                generator.setJavaVersion(req.getJavaVersion());
                generator.setJmeterVersion(req.getJmeterVersion());
                generator.setLastHeartbeatAt(Instant.now());
                if (generator.getStatus() == GeneratorStatus.PREPARING || generator.getStatus() == GeneratorStatus.ERROR) {
                    generator.setStatus(GeneratorStatus.AVAILABLE);
                    generator.setProvisionStep("READY");
                }
                generator.touch();
                generators.save(generator);
                if (generator.getStatus() == GeneratorStatus.OFFLINE) {
                    reconcile.reconcile(genId);
                }

                out.onNext(ControllerMessage.newBuilder()
                        .setRegisterResponse(RegisterResponse.newBuilder()
                                .setAccepted(true)
                                .setMessage("registered")
                                .setSessionId(sessionId)
                                .setClientCertPem(com.google.protobuf.ByteString.copyFrom(issued.certPem()))
                                .setClientKeyPem(com.google.protobuf.ByteString.copyFrom(issued.keyPem()))
                                .setFencingToken(generator.getFencingToken())
                                .build())
                        .build());
                log.info("Agent {} registered for generator {}", agentId, genId);
            }

            private void handleHeartbeat(Heartbeat hb) {
                if (generatorId == null && !hb.getAgentId().isBlank()) {
                    connections.findByAgentId(hb.getAgentId()).ifPresent(c -> generatorId = c.getGeneratorId());
                }
                if (generatorId == null) return;
                sessions.touch(generatorId);
                generatorService.updateHeartbeat(
                        generatorId,
                        hb.getCpuUsagePercent(),
                        hb.getRamUsedMb(),
                        hb.getRamTotalMb(),
                        hb.getDiskFreeMb(),
                        null, null, null
                );
                generators.findById(generatorId).ifPresent(g -> {
                    if (g.getStatus() == GeneratorStatus.OFFLINE) {
                        reconcile.reconcile(generatorId);
                    }
                });
            }

            private void handleResult(CommandResult result) {
                sessions.complete(result.getCommandId(), new CommandOutcome(
                        result.getSuccess(),
                        result.getMessage(),
                        result.getAttributesMap()
                ));
            }

            private void handleLog(LogChunk chunk) {
                logs.append(
                        chunk.getRunId(),
                        chunk.getGeneratorId(),
                        chunk.getLogFile(),
                        chunk.getOffset(),
                        chunk.getData().toByteArray()
                );
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Agent session error generator={} agent={}: {}", generatorId, agentId, t.getMessage());
                if (generatorId != null && sessionId != null) {
                    sessions.unregister(generatorId, sessionId);
                }
            }

            @Override
            public void onCompleted() {
                if (generatorId != null && sessionId != null) {
                    sessions.unregister(generatorId, sessionId);
                }
                responseObserver.onCompleted();
            }
        };
    }
}
