package com.ltplatform.orchestration.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OrchestratorStartupReconcile implements ApplicationRunner {
    private final TestOrchestrator orchestrator;

    public OrchestratorStartupReconcile(TestOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        orchestrator.reconcileActiveRuns();
    }
}
