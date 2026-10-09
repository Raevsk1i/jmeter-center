package com.ltplatform.executionhistory.web;

import com.ltplatform.agent.session.AgentSessionRegistry;
import com.ltplatform.executionhistory.domain.TestRunStatus;
import com.ltplatform.executionhistory.repo.TestRunRepository;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.orchestration.service.TestOrchestrator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final GeneratorRepository generators;
    private final TestRunRepository runs;
    private final TestOrchestrator orchestrator;
    private final AgentSessionRegistry sessions;

    public DashboardController(
            GeneratorRepository generators,
            TestRunRepository runs,
            TestOrchestrator orchestrator,
            AgentSessionRegistry sessions
    ) {
        this.generators = generators;
        this.runs = runs;
        this.orchestrator = orchestrator;
        this.sessions = sessions;
    }

    @GetMapping
    public Map<String, Object> dashboard() {
        Map<String, Long> generatorStatuses = new LinkedHashMap<>();
        Arrays.stream(GeneratorStatus.values())
                .forEach(s -> generatorStatuses.put(s.name(), generators.countByStatus(s)));

        Map<String, Long> runStatuses = new LinkedHashMap<>();
        Arrays.stream(TestRunStatus.values())
                .forEach(s -> runStatuses.put(s.name(), runs.countByStatus(s)));

        return Map.of(
                "generators", generatorStatuses,
                "runs", runStatuses,
                "activeRuns", runs.countByStatus(TestRunStatus.RUNNING),
                "onlineAgents", sessions.snapshot().size(),
                "recentRuns", orchestrator.list().stream().limit(10).toList()
        );
    }
}
