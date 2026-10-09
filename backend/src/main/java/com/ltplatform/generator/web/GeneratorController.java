package com.ltplatform.generator.web;

import com.ltplatform.generator.dto.GeneratorDtos.CreateCredentialRequest;
import com.ltplatform.generator.dto.GeneratorDtos.CreateGeneratorRequest;
import com.ltplatform.generator.dto.GeneratorDtos.CredentialResponse;
import com.ltplatform.generator.dto.GeneratorDtos.GeneratorResponse;
import com.ltplatform.generator.dto.GeneratorDtos.ProvisionStepResponse;
import com.ltplatform.generator.dto.GeneratorDtos.UpdateGeneratorRequest;
import com.ltplatform.generator.service.GeneratorLogService;
import com.ltplatform.generator.service.GeneratorLogService.LogEntry;
import com.ltplatform.generator.service.GeneratorService;
import com.ltplatform.provisioning.service.ProvisioningService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class GeneratorController {
    private final GeneratorService generators;
    private final ProvisioningService provisioning;
    private final GeneratorLogService genLogs;

    public GeneratorController(
            GeneratorService generators,
            ProvisioningService provisioning,
            GeneratorLogService genLogs
    ) {
        this.generators = generators;
        this.provisioning = provisioning;
        this.genLogs = genLogs;
    }

    @GetMapping("/generators")
    public List<GeneratorResponse> list() {
        return generators.list();
    }

    @GetMapping("/generators/{id}")
    public GeneratorResponse get(@PathVariable UUID id) {
        return generators.get(id);
    }

    @PostMapping("/generators")
    public GeneratorResponse create(@Valid @RequestBody CreateGeneratorRequest req, Authentication auth) {
        return generators.create(req, auth.getName());
    }

    @PutMapping("/generators/{id}")
    public GeneratorResponse update(@PathVariable UUID id, @RequestBody UpdateGeneratorRequest req, Authentication auth) {
        return generators.update(id, req, auth.getName());
    }

    @DeleteMapping("/generators/{id}")
    public Map<String, String> delete(@PathVariable UUID id, Authentication auth) {
        generators.delete(id, auth.getName());
        return Map.of("status", "deleted");
    }

    @PostMapping("/generators/{id}/provision")
    public Map<String, String> provision(@PathVariable UUID id, Authentication auth) {
        generators.reprovision(id, auth.getName());
        return Map.of("status", "provisioning");
    }

    @GetMapping("/generators/{id}/provision/steps")
    public List<ProvisionStepResponse> steps(@PathVariable UUID id) {
        return provisioning.listSteps(id);
    }

    @PostMapping("/generators/{id}/check")
    public Map<String, Object> check(@PathVariable UUID id) {
        return provisioning.checkConnectivity(id);
    }

    @GetMapping("/generators/{id}/logs")
    public List<LogEntry> logs(
            @PathVariable UUID id,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "300") int limit
    ) {
        generators.get(id); // 404 if missing
        return genLogs.list(id, source, afterId, limit);
    }

    @GetMapping("/generators/{id}/logs/sources")
    public Map<String, Object> logSources(@PathVariable UUID id) {
        generators.get(id);
        return genLogs.sources(id);
    }

    @GetMapping(path = "/generators/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable UUID id) throws IOException {
        generators.get(id);
        SseEmitter emitter = new SseEmitter(0L);
        AutoCloseable sub = genLogs.subscribe(id, entry -> {
            try {
                emitter.send(SseEmitter.event().name("log").data(entry));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        emitter.onCompletion(() -> closeQuietly(sub));
        emitter.onTimeout(() -> closeQuietly(sub));
        // seed last lines
        for (LogEntry e : genLogs.list(id, null, null, 50)) {
            emitter.send(SseEmitter.event().name("log").data(e));
        }
        emitter.send(SseEmitter.event().name("ready").data(Map.of("generatorId", id.toString())));
        return emitter;
    }

    @PostMapping("/ssh-credentials")
    public CredentialResponse createCred(@Valid @RequestBody CreateCredentialRequest req, Authentication auth) {
        return generators.createCredential(req, auth.getName());
    }

    @GetMapping("/ssh-credentials")
    public List<CredentialResponse> listCreds() {
        return generators.listCredentials();
    }

    private static void closeQuietly(AutoCloseable c) {
        try { c.close(); } catch (Exception ignored) {}
    }
}
