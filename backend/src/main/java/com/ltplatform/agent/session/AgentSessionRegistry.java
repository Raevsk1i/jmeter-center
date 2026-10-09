package com.ltplatform.agent.session;

import com.ltplatform.agent.v1.ControllerMessage;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class AgentSessionRegistry {
    private final Map<UUID, AgentSession> byGenerator = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CommandOutcome>> pending = new ConcurrentHashMap<>();

    public void register(UUID generatorId, String agentId, String sessionId, StreamObserver<ControllerMessage> outbound) {
        byGenerator.put(generatorId, new AgentSession(generatorId, agentId, sessionId, outbound, Instant.now()));
    }

    public void unregister(UUID generatorId, String sessionId) {
        AgentSession existing = byGenerator.get(generatorId);
        if (existing != null && existing.sessionId().equals(sessionId)) {
            byGenerator.remove(generatorId);
        }
    }

    /**
     * Drop the live session so the agent reconnects and re-adopts the controller fencing token.
     * Used when the agent's persisted token is ahead of the controller (e.g. after DB reset).
     */
    public void disconnect(UUID generatorId) {
        AgentSession session = byGenerator.remove(generatorId);
        if (session == null) {
            return;
        }
        try {
            session.outbound().onCompleted();
        } catch (Exception ignored) {
            try {
                session.outbound().onError(new IllegalStateException("fencing resync"));
            } catch (Exception ignored2) {
                // stream already closed
            }
        }
    }

    public void touch(UUID generatorId) {
        AgentSession s = byGenerator.get(generatorId);
        if (s != null) {
            byGenerator.put(generatorId, new AgentSession(s.generatorId(), s.agentId(), s.sessionId(), s.outbound(), Instant.now()));
        }
    }

    public Optional<AgentSession> get(UUID generatorId) {
        return Optional.ofNullable(byGenerator.get(generatorId));
    }

    public boolean isOnline(UUID generatorId) {
        return byGenerator.containsKey(generatorId);
    }

    public void send(UUID generatorId, ControllerMessage message) {
        AgentSession session = byGenerator.get(generatorId);
        if (session == null) {
            throw new IllegalStateException("Agent offline for generator " + generatorId);
        }
        synchronized (session.outbound()) {
            session.outbound().onNext(message);
        }
    }

    public CompletableFuture<CommandOutcome> awaitResult(String commandId, long timeoutSeconds) {
        CompletableFuture<CommandOutcome> future = new CompletableFuture<>();
        pending.put(commandId, future);
        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((r, e) -> pending.remove(commandId));
    }

    public void complete(String commandId, CommandOutcome outcome) {
        CompletableFuture<CommandOutcome> future = pending.remove(commandId);
        if (future != null) {
            future.complete(outcome);
        }
    }

    public Map<UUID, AgentSession> snapshot() {
        return Map.copyOf(byGenerator);
    }

    public record AgentSession(
            UUID generatorId,
            String agentId,
            String sessionId,
            StreamObserver<ControllerMessage> outbound,
            Instant lastSeen
    ) {}

    public record CommandOutcome(boolean success, String message, Map<String, String> attributes) {}
}
