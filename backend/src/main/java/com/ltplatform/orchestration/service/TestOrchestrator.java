package com.ltplatform.orchestration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltplatform.agent.service.AgentCommandService;
import com.ltplatform.agent.session.AgentSessionRegistry.CommandOutcome;
import com.ltplatform.bitbucket.service.BitbucketService;
import com.ltplatform.common.ApiException;
import com.ltplatform.executionhistory.domain.RunGenerator;
import com.ltplatform.executionhistory.domain.TestRun;
import com.ltplatform.executionhistory.domain.TestRunStatus;
import com.ltplatform.executionhistory.repo.RunGeneratorRepository;
import com.ltplatform.executionhistory.repo.TestRunRepository;
import com.ltplatform.executionhistory.service.ExecutionEventService;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.generator.service.GeneratorLogService;
import com.ltplatform.orchestration.service.ArtifactPackageService.PackagedArtifact;
import com.ltplatform.reservation.domain.GeneratorRole;
import com.ltplatform.reservation.service.ReservationService;
import com.ltplatform.reservation.service.ReservationService.ReservationRequest;
import com.ltplatform.reservation.service.ReservationService.ReservationResult;
import com.ltplatform.settings.service.SettingsService;
import com.ltplatform.testmanagement.domain.SystemEntity;
import com.ltplatform.testmanagement.domain.TestDefinition;
import com.ltplatform.testmanagement.service.TestManagementService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TestOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(TestOrchestrator.class);

    private final TestRunRepository runs;
    private final RunGeneratorRepository runGenerators;
    private final TestManagementService tests;
    private final ReservationService reservations;
    private final BitbucketService bitbucket;
    private final ArtifactPackageService artifacts;
    private final AgentCommandService commands;
    private final ExecutionEventService events;
    private final GeneratorRepository generators;
    private final GeneratorLogService genLogs;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final OrchestrationLauncher launcher;

    public TestOrchestrator(
            TestRunRepository runs,
            RunGeneratorRepository runGenerators,
            TestManagementService tests,
            ReservationService reservations,
            BitbucketService bitbucket,
            ArtifactPackageService artifacts,
            AgentCommandService commands,
            ExecutionEventService events,
            GeneratorRepository generators,
            GeneratorLogService genLogs,
            SettingsService settings,
            ObjectMapper mapper,
            @org.springframework.context.annotation.Lazy OrchestrationLauncher launcher
    ) {
        this.runs = runs;
        this.runGenerators = runGenerators;
        this.tests = tests;
        this.reservations = reservations;
        this.bitbucket = bitbucket;
        this.artifacts = artifacts;
        this.commands = commands;
        this.events = events;
        this.generators = generators;
        this.genLogs = genLogs;
        this.settings = settings;
        this.mapper = mapper;
        this.launcher = launcher;
    }

    public record CreateRunRequest(
            UUID testDefinitionId,
            UUID masterGeneratorId,
            List<UUID> slaveGeneratorIds,
            Map<String, String> properties,
            boolean startNow
    ) {}

    public record RunResponse(
            UUID id,
            UUID testDefinitionId,
            TestRunStatus status,
            String commitHash,
            Map<String, Object> configuration,
            UUID masterGeneratorId,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            String createdBy,
            Instant createdAt
    ) {}

    @Transactional
    public RunResponse create(CreateRunRequest req, String actor) {
        TestDefinition def = tests.requireTest(req.testDefinitionId());
        if (req.masterGeneratorId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Master generator required");
        }
        Map<String, Object> config = new HashMap<>();
        config.put("masterGeneratorId", req.masterGeneratorId().toString());
        config.put("slaveGeneratorIds", req.slaveGeneratorIds() == null ? List.of() :
                req.slaveGeneratorIds().stream().map(UUID::toString).toList());
        config.put("properties", req.properties() != null ? req.properties() : Map.of());
        config.put("jmxPath", def.getJmxPath());
        config.put("systemId", def.getSystemId().toString());

        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setTestDefinitionId(def.getId());
        run.setStatus(TestRunStatus.SCHEDULED);
        run.setMasterGeneratorId(req.masterGeneratorId());
        run.setCreatedBy(actor);
        try {
            run.setConfiguration(mapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid configuration");
        }
        runs.saveAndFlush(run);

        saveRunGenerators(run.getId(), req.masterGeneratorId(), req.slaveGeneratorIds());
        events.emit(run.getId(), null, "TestRun", run.getId().toString(), "CREATED", "Run created", config);

        if (req.startNow()) {
            UUID id = run.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        launcher.start(id);
                    }
                });
            } else {
                launcher.start(id);
            }
        }
        return toResponse(run);
    }

    public void startAsync(UUID runId) {
        launcher.start(runId);
    }

    public void failPublic(UUID runId, String message) {
        fail(runId, message);
    }

    public void stopInternalPublic(UUID runId, boolean force) {
        stopInternal(runId, force);
    }

    public void execute(UUID runId) throws Exception {
        TestRun run = runs.findById(runId).orElseThrow();
        if (run.getStatus() != TestRunStatus.SCHEDULED && run.getStatus() != TestRunStatus.PREPARING) {
            throw new ApiException(HttpStatus.CONFLICT, "Run not startable in status " + run.getStatus());
        }
        transition(run, TestRunStatus.PREPARING);
        events.emit(runId, null, "TestRun", runId.toString(), "PREPARING", "Starting orchestration", null);

        Map<String, Object> config = readConfig(run);
        UUID masterId = UUID.fromString(String.valueOf(config.get("masterGeneratorId")));
        @SuppressWarnings("unchecked")
        List<String> slaveStr = (List<String>) config.getOrDefault("slaveGeneratorIds", List.of());
        List<UUID> slaves = slaveStr.stream().map(UUID::fromString).toList();

        List<ReservationRequest> reqs = new ArrayList<>();
        reqs.add(new ReservationRequest(masterId, GeneratorRole.MASTER));
        for (UUID s : slaves) {
            reqs.add(new ReservationRequest(s, GeneratorRole.SLAVE));
        }
        List<ReservationResult> reserved;
        try {
            reserved = reservations.acquire(runId, reqs);
        } catch (ApiException e) {
            transition(run, TestRunStatus.FAILED, e.getMessage().contains("AVAILABLE") || e.getMessage().contains("reserved")
                    ? "RESOURCES_UNAVAILABLE: " + e.getMessage() : e.getMessage());
            events.emit(runId, null, "TestRun", runId.toString(), "RESERVE_FAILED", e.getMessage(), null);
            return;
        }
        events.emit(runId, null, "TestRun", runId.toString(), "RESERVED",
                "Generators reserved: master=" + shortId(masterId) + " slaves=" + slaves.size(),
                Map.of("count", reserved.size(), "masterGeneratorId", masterId.toString(),
                        "slaveGeneratorIds", slaves.stream().map(UUID::toString).toList()));
        orchLog(masterId, runId, "RESERVED",
                "Reserved master + " + slaves.size() + " slave(s) for run");

        TestDefinition def = tests.requireTest(run.getTestDefinitionId());
        SystemEntity system = tests.requireSystem(def.getSystemId());
        String commit = bitbucket.resolveCommit(system.getBitbucketBranch());
        run.setCommitHash(commit);
        runs.save(run);

        String testRoot = def.getJmxPath().contains("/")
                ? def.getJmxPath().substring(0, def.getJmxPath().indexOf('/'))
                : (def.getTestType().name().equals("STABILITY") ? "stability_test" : "perf_test");
        if (def.getJmxPath().contains("/")) {
            testRoot = def.getJmxPath().substring(0, def.getJmxPath().indexOf('/'));
        }

        String jmxRelative = def.getJmxPath().contains("/")
                ? def.getJmxPath().substring(def.getJmxPath().indexOf('/') + 1)
                : "test.jmx";

        List<PackagedArtifact> packaged = artifacts.buildPackage(runId, commit, testRoot);
        List<String> fileNames = packaged.stream().map(PackagedArtifact::relativePath).toList();
        long totalBytes = packaged.stream().mapToLong(a -> a.content() != null ? a.content().length : 0).sum();
        boolean jmxInPackage = packaged.stream().anyMatch(a ->
                a.relativePath().equals(jmxRelative) || a.relativePath().endsWith("/" + jmxRelative));
        Map<String, Object> artifactDetail = new LinkedHashMap<>();
        artifactDetail.put("files", packaged.size());
        artifactDetail.put("totalBytes", totalBytes);
        artifactDetail.put("commit", commit);
        artifactDetail.put("testRoot", testRoot);
        artifactDetail.put("jmxPath", def.getJmxPath());
        artifactDetail.put("jmxRelative", jmxRelative);
        artifactDetail.put("jmxInPackage", jmxInPackage);
        artifactDetail.put("names", fileNames);
        events.emit(runId, null, "TestRun", runId.toString(), "ARTIFACTS",
                "Package built: " + packaged.size() + " files, " + totalBytes + " bytes"
                        + (jmxInPackage ? "; jmx OK" : "; WARNING jmx missing: " + jmxRelative),
                artifactDetail);
        orchLog(masterId, runId, "ARTIFACTS",
                "Package " + packaged.size() + " files (" + totalBytes + " B) commit=" + shortId(commit)
                        + " jmx=" + jmxRelative + (jmxInPackage ? " present" : " MISSING"));
        if (!jmxInPackage) {
            throw new IllegalStateException("JMX not in artifact package: " + jmxRelative
                    + " (files=" + fileNames + ")");
        }

        String workspace = "/var/lib/lt-agent/workspaces/" + runId;
        Map<UUID, Long> tokens = new HashMap<>();
        reserved.forEach(r -> tokens.put(r.generatorId(), r.fencingToken()));

        for (ReservationResult r : reserved) {
            String role = r.role().name();
            CommandOutcome prep = commands.prepareWorkspace(r.generatorId(), runId.toString(), r.fencingToken(), workspace);
            emitCmd(runId, r.generatorId(), "PREPARE", prep);
            if (!prep.success()) {
                throw new IllegalStateException("Prepare failed on " + r.generatorId() + ": " + prep.message());
            }
            CommandOutcome sync = commands.syncArtifacts(r.generatorId(), runId.toString(), r.fencingToken(), workspace,
                    artifacts.toProto(packaged));
            emitCmd(runId, r.generatorId(), "SYNC", sync);
            if (!sync.success()) {
                throw new IllegalStateException("Sync failed on " + r.generatorId() + ": " + sync.message());
            }
            CommandOutcome verify = commands.verifyEnvironment(r.generatorId(), runId.toString(), r.fencingToken());
            emitCmd(runId, r.generatorId(), "VERIFY", verify);
            if (!verify.success()) {
                throw new IllegalStateException("Verify failed on " + r.generatorId() + ": " + verify.message());
            }
            Map<String, Object> readyDetail = new LinkedHashMap<>();
            readyDetail.put("role", role);
            readyDetail.put("workspace", sync.attributes().getOrDefault("workspace", workspace));
            readyDetail.put("fileCount", sync.attributes().getOrDefault("fileCount", String.valueOf(packaged.size())));
            readyDetail.put("java", verify.attributes().getOrDefault("java", ""));
            readyDetail.put("jmeter", verify.attributes().getOrDefault("jmeter", ""));
            readyDetail.put("jmeterHome", verify.attributes().getOrDefault("jmeterHome", ""));
            events.emit(runId, r.generatorId(), "Generator", r.generatorId().toString(), "READY",
                    role + " agent ready (java=" + readyDetail.get("java") + ", jmeter=" + readyDetail.get("jmeter") + ")",
                    readyDetail);
            orchLog(r.generatorId(), runId, "READY",
                    role + " ready workspace=" + readyDetail.get("workspace")
                            + " files=" + readyDetail.get("fileCount"));
        }

        Map<String, Object> jmeterSettings = settings.getMap("jmeter");
        int rmiPort = ((Number) jmeterSettings.getOrDefault("rmiPort", 1099)).intValue();
        int localPort = ((Number) jmeterSettings.getOrDefault("localPort", 4000)).intValue();

        List<String> remoteHosts = new ArrayList<>();
        for (ReservationResult r : reserved) {
            if (r.role() == GeneratorRole.SLAVE) {
                Generator g = generators.findById(r.generatorId()).orElseThrow();
                remoteHosts.add(g.getHostname() + ":" + rmiPort);
                CommandOutcome startServer = commands.startServer(
                        r.generatorId(), runId.toString(), r.fencingToken(), workspace, rmiPort, localPort, List.of());
                emitCmd(runId, r.generatorId(), "START_SERVER", startServer);
                if (!startServer.success()) {
                    throw new IllegalStateException("JMeter server start failed on " + r.generatorId() + ": " + startServer.message());
                }
                events.emit(runId, r.generatorId(), "Generator", r.generatorId().toString(), "SLAVE_STARTED",
                        "jmeter-server pid=" + startServer.attributes().getOrDefault("pid", "?")
                                + " host=" + g.getHostname() + ":" + rmiPort,
                        attrsAsMap(startServer.attributes()));
                commands.requestLogStream(r.generatorId(), runId.toString(), r.fencingToken(), 0, "jmeter.log");
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, String> properties = (Map<String, String>) config.getOrDefault("properties", Map.of());

        Map<String, Object> startPlan = new LinkedHashMap<>();
        startPlan.put("jmxRelative", jmxRelative);
        startPlan.put("workspace", workspace);
        startPlan.put("remoteHosts", remoteHosts);
        startPlan.put("properties", properties);
        startPlan.put("rmiPort", rmiPort);
        events.emit(runId, masterId, "TestRun", runId.toString(), "START_TEST",
                "Starting JMeter master jmx=" + jmxRelative + " remotes=" + remoteHosts.size(),
                startPlan);
        orchLog(masterId, runId, "START_TEST",
                "StartJMeterTest jmx=" + jmxRelative + " remotes=" + remoteHosts + " props=" + properties.size());

        CommandOutcome startTest = commands.startTest(
                masterId, runId.toString(), tokens.get(masterId), workspace, jmxRelative, remoteHosts, properties, rmiPort);
        emitCmd(runId, masterId, "START_TEST", startTest);
        if (!startTest.success()) {
            throw new IllegalStateException("JMeter test start failed: " + startTest.message());
        }

        Map<String, Object> startedDetail = attrsAsMap(startTest.attributes());
        startedDetail.put("jmxRelative", jmxRelative);
        startedDetail.put("remoteHosts", remoteHosts);
        events.emit(runId, masterId, "TestRun", runId.toString(), "MASTER_STARTED",
                "JMeter started pid=" + startTest.attributes().getOrDefault("pid", "?")
                        + " argv=" + truncate(startTest.attributes().getOrDefault("argv", ""), 300),
                startedDetail);
        orchLog(masterId, runId, "MASTER_STARTED",
                "pid=" + startTest.attributes().getOrDefault("pid", "?")
                        + " jmx=" + startTest.attributes().getOrDefault("jmxPath", jmxRelative)
                        + " argv=" + truncate(startTest.attributes().getOrDefault("argv", ""), 400));

        // Pull jmeter.log + console output into controller log storage for the Live Run panel.
        commands.requestLogStream(masterId, runId.toString(), tokens.get(masterId), 0, "jmeter.log");
        commands.requestLogStream(masterId, runId.toString(), tokens.get(masterId), 0, "jmeter-console.log");

        reservations.markRunning(runId);
        run.setStartedAt(Instant.now());
        transition(run, TestRunStatus.RUNNING);
        events.emit(runId, masterId, "TestRun", runId.toString(), "RUNNING",
                "Distributed test running; streaming agent logs",
                Map.of("pid", startTest.attributes().getOrDefault("pid", "")));

        Map<String, String> lastAttrs = new LinkedHashMap<>(startTest.attributes());
        int pollCount = 0;
        boolean sawRunning = false;
        while (true) {
            TestRun current = runs.findById(runId).orElseThrow();
            if (current.getStatus() == TestRunStatus.CANCELLED) {
                stopInternal(runId, true);
                return;
            }
            CommandOutcome status = commands.getExecutionStatus(masterId, runId.toString(), tokens.get(masterId));
            lastAttrs = status.attributes() != null ? new LinkedHashMap<>(status.attributes()) : new LinkedHashMap<>();
            boolean alive = Boolean.parseBoolean(lastAttrs.getOrDefault("jmeterAlive", "false"));
            String state = lastAttrs.getOrDefault("state", "UNKNOWN");
            if ("RUNNING".equalsIgnoreCase(state) || alive) {
                sawRunning = true;
            }
            pollCount++;
            if (pollCount == 1 || pollCount % 3 == 0 || !alive || !"RUNNING".equalsIgnoreCase(state)) {
                Map<String, Object> pollDetail = attrsAsMap(lastAttrs);
                pollDetail.put("poll", pollCount);
                pollDetail.put("sawRunning", sawRunning);
                String pollMsg = "Poll #" + pollCount
                        + " state=" + state
                        + " alive=" + alive
                        + " exit=" + lastAttrs.getOrDefault("exitCode", "?")
                        + " jtlSamples=" + lastAttrs.getOrDefault("jtlSamples", "?")
                        + " jtlBytes=" + lastAttrs.getOrDefault("jtlBytes", "?");
                events.emit(runId, masterId, "TestRun", runId.toString(), "STATUS_POLL", pollMsg, pollDetail);
                orchLog(masterId, runId, "STATUS_POLL", pollMsg);
            }
            if ("FAILED".equalsIgnoreCase(state) || "COMPLETED".equalsIgnoreCase(state)) {
                break;
            }
            if (!status.success() && status.message() != null && status.message().contains("not found")) {
                events.emit(runId, masterId, "TestRun", runId.toString(), "STATUS_ERROR",
                        "GetExecutionStatus failed: " + status.message(), attrsAsMap(lastAttrs));
                break;
            }
            if ("IDLE".equalsIgnoreCase(state) && !sawRunning && pollCount >= 4) {
                Map<String, Object> idleDetail = attrsAsMap(lastAttrs);
                events.emit(runId, masterId, "TestRun", runId.toString(), "NEVER_STARTED",
                        "JMeter still IDLE after " + pollCount + " polls — process never reported RUNNING",
                        idleDetail);
                cleanupAndComplete(runId, tokens, workspace, TestRunStatus.FAILED,
                        "JMeter never started (IDLE after " + pollCount + " status polls). "
                                + "Check agent StartJMeterTest result and jmeter-console.log.");
                return;
            }
            Thread.sleep(5000);
        }

        int exitCode = parseInt(lastAttrs.get("exitCode"), 0);
        int jtlSamples = parseInt(lastAttrs.get("jtlSamples"), -1);
        long jtlBytes = parseLong(lastAttrs.get("jtlBytes"), -1);
        Map<String, Object> finishDetail = attrsAsMap(lastAttrs);
        finishDetail.put("sawRunning", sawRunning);
        finishDetail.put("pollCount", pollCount);

        TestRunStatus terminal = TestRunStatus.COMPLETED;
        String terminalMsg = null;
        if (exitCode != 0 || "FAILED".equalsIgnoreCase(lastAttrs.getOrDefault("state", ""))) {
            terminal = TestRunStatus.FAILED;
            terminalMsg = "JMeter exited with code " + exitCode
                    + (lastAttrs.containsKey("jtlError") ? "; jtl=" + lastAttrs.get("jtlError") : "");
        } else if (jtlSamples == 0 || (jtlSamples < 0 && jtlBytes <= 0)) {
            terminal = TestRunStatus.FAILED;
            terminalMsg = "JMeter finished with exit=0 but results.jtl has 0 samples "
                    + "(bytes=" + jtlBytes + ") — configured requests were not recorded. "
                    + "Inspect STATUS_POLL / jmeterLogTail and agent console log.";
        }

        events.emit(runId, masterId, "TestRun", runId.toString(), "FINISH_CHECK",
                "exit=" + exitCode + " jtlSamples=" + jtlSamples + " jtlBytes=" + jtlBytes
                        + " → " + terminal.name(),
                finishDetail);
        orchLog(masterId, runId, "FINISH_CHECK",
                "exit=" + exitCode + " jtlSamples=" + jtlSamples + " → " + terminal.name()
                        + (terminalMsg != null ? " (" + terminalMsg + ")" : ""));

        cleanupAndComplete(runId, tokens, workspace, terminal, terminalMsg);
    }

    @Transactional
    public RunResponse stop(UUID runId, boolean force, String actor) {
        TestRun run = runs.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Run not found"));
        if (run.getStatus() != TestRunStatus.RUNNING && run.getStatus() != TestRunStatus.PREPARING) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot stop run in status " + run.getStatus());
        }
        transition(run, TestRunStatus.CANCELLED, "Cancelled by " + actor);
        events.emit(runId, null, "TestRun", runId.toString(), "CANCEL_REQUESTED", "Stop requested", Map.of("force", force));
        launcher.stop(runId, force);
        return toResponse(run);
    }

    private void stopInternal(UUID runId, boolean force) {
        Map<UUID, Long> tokens = new HashMap<>();
        reservations.heldForRun(runId).forEach(r -> tokens.put(r.getGeneratorId(), r.getFencingToken()));
        String workspace = "/var/lib/lt-agent/workspaces/" + runId;
        for (var entry : tokens.entrySet()) {
            commands.stop(entry.getKey(), runId.toString(), entry.getValue(), force);
        }
        cleanupAndComplete(runId, tokens, workspace, TestRunStatus.CANCELLED, "Cancelled");
    }

    private void cleanupAndComplete(UUID runId, Map<UUID, Long> tokens, String workspace,
                                    TestRunStatus terminal, String error) {
        for (var entry : tokens.entrySet()) {
            try {
                commands.stop(entry.getKey(), runId.toString(), entry.getValue(), true);
                commands.cleanup(entry.getKey(), runId.toString(), entry.getValue(), workspace, true);
            } catch (Exception e) {
                log.warn("Cleanup warning on {}: {}", entry.getKey(), e.getMessage());
            }
        }
        reservations.release(runId);
        runs.findById(runId).ifPresent(run -> {
            run.setFinishedAt(Instant.now());
            transition(run, terminal, error);
            Map<String, Object> detail = new LinkedHashMap<>();
            if (error != null) detail.put("error", error);
            detail.put("status", terminal.name());
            events.emit(runId, null, "TestRun", runId.toString(), terminal.name(),
                    error != null ? error : "Run finished", detail);
            if (run.getMasterGeneratorId() != null) {
                orchLog(run.getMasterGeneratorId(), runId, terminal.name(),
                        error != null ? error : "Run finished as " + terminal.name());
            }
        });
    }

    private void emitCmd(UUID runId, UUID generatorId, String eventType, CommandOutcome outcome) {
        Map<String, Object> detail = attrsAsMap(outcome.attributes());
        detail.put("success", outcome.success());
        detail.put("message", outcome.message());
        events.emit(runId, generatorId, "Generator", generatorId.toString(), eventType,
                (outcome.success() ? "OK " : "FAIL ") + eventType
                        + (outcome.message() != null && !outcome.message().isBlank()
                        ? ": " + truncate(outcome.message(), 300) : ""),
                detail);
    }

    private void orchLog(UUID generatorId, UUID runId, String eventType, String message) {
        if (generatorId == null) return;
        genLogs.append(generatorId, "ORCHESTRATION", "INFO", eventType,
                "[run " + shortId(runId.toString()) + "] " + message,
                Map.of("runId", runId.toString()));
    }

    private static Map<String, Object> attrsAsMap(Map<String, String> attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attrs == null) return out;
        // Keep tails but cap extreme size in event JSON
        attrs.forEach((k, v) -> {
            if (v != null && (k.endsWith("Tail") || "argv".equals(k)) && v.length() > 3500) {
                out.put(k, v.substring(v.length() - 3500));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static int parseInt(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String shortId(UUID id) {
        return id == null ? "" : shortId(id.toString());
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void fail(UUID runId, String message) {
        try {
            Map<UUID, Long> tokens = new HashMap<>();
            reservations.heldForRun(runId).forEach(r -> tokens.put(r.getGeneratorId(), r.getFencingToken()));
            String workspace = "/var/lib/lt-agent/workspaces/" + runId;
            cleanupAndComplete(runId, tokens, workspace, TestRunStatus.FAILED, message);
        } catch (Exception e) {
            runs.findById(runId).ifPresent(run -> transition(run, TestRunStatus.FAILED, message));
            try { reservations.release(runId); } catch (Exception ignored) {}
        }
    }

    @Transactional(readOnly = true)
    public List<RunResponse> list() {
        return runs.findRecent().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RunResponse get(UUID id) {
        return toResponse(runs.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Run not found")));
    }

    @Transactional
    public void reconcileActiveRuns() {
        List<TestRun> active = runs.findByStatusIn(List.of(TestRunStatus.RUNNING, TestRunStatus.PREPARING));
        for (TestRun run : active) {
            events.emit(run.getId(), null, "TestRun", run.getId().toString(), "RECONCILE",
                    "Controller restarted; reconciling run without auto-stop", null);
            // Do not stop JMeter — query status when agents reconnect
        }
    }

    private void saveRunGenerators(UUID runId, UUID master, List<UUID> slaves) {
        RunGenerator m = new RunGenerator();
        m.setId(UUID.randomUUID());
        m.setRunId(runId);
        m.setGeneratorId(master);
        m.setRole(GeneratorRole.MASTER);
        runGenerators.save(m);
        if (slaves != null) {
            for (UUID s : slaves) {
                RunGenerator rg = new RunGenerator();
                rg.setId(UUID.randomUUID());
                rg.setRunId(runId);
                rg.setGeneratorId(s);
                rg.setRole(GeneratorRole.SLAVE);
                runGenerators.save(rg);
            }
        }
    }

    private void transition(TestRun run, TestRunStatus status) {
        transition(run, status, null);
    }

    private void transition(TestRun run, TestRunStatus status, String error) {
        run.setStatus(status);
        if (error != null) run.setErrorMessage(error);
        run.touch();
        runs.save(run);
    }

    private Map<String, Object> readConfig(TestRun run) {
        try {
            return mapper.readValue(run.getConfiguration(), new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private RunResponse toResponse(TestRun run) {
        Map<String, Object> config = readConfig(run);
        return new RunResponse(
                run.getId(), run.getTestDefinitionId(), run.getStatus(), run.getCommitHash(),
                config, run.getMasterGeneratorId(), run.getStartedAt(), run.getFinishedAt(),
                run.getErrorMessage(), run.getCreatedBy(), run.getCreatedAt()
        );
    }
}
