package com.ltplatform.orchestration.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class OrchestrationLauncher {
    private static final Logger log = LoggerFactory.getLogger(OrchestrationLauncher.class);
    private final TestOrchestrator orchestrator;

    public OrchestrationLauncher(@Lazy TestOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Async("orchestrationExecutor")
    public void start(UUID runId) {
        try {
            orchestrator.execute(runId);
        } catch (Exception e) {
            log.error("Run {} failed: {}", runId, e.getMessage(), e);
            orchestrator.failPublic(runId, e.getMessage());
        }
    }

    @Async("orchestrationExecutor")
    public void stop(UUID runId, boolean force) {
        try {
            orchestrator.stopInternalPublic(runId, force);
        } catch (Exception e) {
            log.error("Stop failed for {}: {}", runId, e.getMessage());
            orchestrator.failPublic(runId, e.getMessage());
        }
    }
}
