package com.ltplatform.executionhistory.web;

import com.ltplatform.executionhistory.service.ExecutionEventService;
import com.ltplatform.logging.service.LogStorageService;
import com.ltplatform.orchestration.service.TestOrchestrator;
import com.ltplatform.orchestration.service.TestOrchestrator.CreateRunRequest;
import com.ltplatform.orchestration.service.TestOrchestrator.RunResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {
    private final TestOrchestrator orchestrator;
    private final ExecutionEventService events;
    private final LogStorageService logs;

    public RunController(TestOrchestrator orchestrator, ExecutionEventService events, LogStorageService logs) {
        this.orchestrator = orchestrator;
        this.events = events;
        this.logs = logs;
    }

    @GetMapping
    public List<RunResponse> list() {
        return orchestrator.list();
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable UUID id) {
        return orchestrator.get(id);
    }

    @PostMapping
    public RunResponse create(@RequestBody CreateRunRequest req, Authentication auth) {
        return orchestrator.create(req, auth.getName());
    }

    @PostMapping("/{id}/start")
    public RunResponse start(@PathVariable UUID id) {
        orchestrator.startAsync(id);
        return orchestrator.get(id);
    }

    @PostMapping("/{id}/stop")
    public RunResponse stop(@PathVariable UUID id,
                            @RequestParam(defaultValue = "false") boolean force,
                            Authentication auth) {
        return orchestrator.stop(id, force, auth.getName());
    }

    @PostMapping("/{id}/rerun")
    public RunResponse rerun(@PathVariable UUID id, Authentication auth) {
        RunResponse existing = orchestrator.get(id);
        @SuppressWarnings("unchecked")
        List<String> slaves = (List<String>) existing.configuration().getOrDefault("slaveGeneratorIds", List.of());
        @SuppressWarnings("unchecked")
        Map<String, String> props = (Map<String, String>) existing.configuration().getOrDefault("properties", Map.of());
        return orchestrator.create(new CreateRunRequest(
                existing.testDefinitionId(),
                existing.masterGeneratorId(),
                slaves.stream().map(UUID::fromString).toList(),
                props,
                true
        ), auth.getName());
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable UUID id) {
        return events.listForRun(id);
    }

    @GetMapping("/{id}/logs")
    public Map<String, Object> logs(
            @PathVariable UUID id,
            @RequestParam UUID generatorId,
            @RequestParam(defaultValue = "jmeter.log") String file,
            @RequestParam(defaultValue = "0") long fromOffset,
            @RequestParam(defaultValue = "65536") int maxBytes
    ) {
        String content = logs.read(id.toString(), generatorId.toString(), file, fromOffset, maxBytes);
        return Map.of("content", content, "fromOffset", fromOffset);
    }

    @GetMapping(path = "/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable UUID id) throws IOException {
        SseEmitter emitter = new SseEmitter(0L);
        AutoCloseable sub = logs.subscribe(id.toString(), event -> {
            try {
                emitter.send(SseEmitter.event().name("log").data(event));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        emitter.onCompletion(() -> closeQuietly(sub));
        emitter.onTimeout(() -> closeQuietly(sub));
        emitter.send(SseEmitter.event().name("ready").data(Map.of("runId", id.toString())));
        return emitter;
    }

    private static void closeQuietly(AutoCloseable c) {
        try { c.close(); } catch (Exception ignored) {}
    }
}
