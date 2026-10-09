package com.ltplatform.agent.service;

import com.google.protobuf.ByteString;
import com.ltplatform.agent.session.AgentSessionRegistry;
import com.ltplatform.agent.session.AgentSessionRegistry.CommandOutcome;
import com.ltplatform.agent.v1.ArtifactFile;
import com.ltplatform.agent.v1.CleanupExecutionCmd;
import com.ltplatform.agent.v1.CommandEnvelope;
import com.ltplatform.agent.v1.ControllerMessage;
import com.ltplatform.agent.v1.GetExecutionStatusCmd;
import com.ltplatform.agent.v1.PrepareWorkspaceCmd;
import com.ltplatform.agent.v1.StartJMeterServerCmd;
import com.ltplatform.agent.v1.StartJMeterTestCmd;
import com.ltplatform.agent.v1.StopJMeterCmd;
import com.ltplatform.agent.v1.StreamLogsCmd;
import com.ltplatform.agent.v1.SyncArtifactsCmd;
import com.ltplatform.agent.v1.VerifyEnvironmentCmd;
import com.ltplatform.generator.service.GeneratorLogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AgentCommandService {
    private final AgentSessionRegistry sessions;
    private final GeneratorLogService genLogs;

    public AgentCommandService(AgentSessionRegistry sessions, GeneratorLogService genLogs) {
        this.sessions = sessions;
        this.genLogs = genLogs;
    }

    public CommandOutcome prepareWorkspace(UUID generatorId, String runId, long fencingToken, String workspace) {
        return send(generatorId, runId, fencingToken, "PrepareWorkspace", b -> b.setPrepareWorkspace(
                PrepareWorkspaceCmd.newBuilder().setWorkspacePath(workspace).build()), 60);
    }

    public CommandOutcome syncArtifacts(UUID generatorId, String runId, long fencingToken, String workspace,
                                        List<ArtifactFile> files) {
        List<String> names = files.stream().map(ArtifactFile::getRelativePath).toList();
        long totalBytes = files.stream().mapToLong(f -> f.getContent().size()).sum();
        return send(generatorId, runId, fencingToken, "SyncArtifacts", b -> b.setSyncArtifacts(
                SyncArtifactsCmd.newBuilder().setWorkspacePath(workspace).addAllFiles(files).build()), 120,
                Map.of(
                        "workspace", workspace,
                        "fileCount", files.size(),
                        "totalBytes", totalBytes,
                        "files", names
                ));
    }

    public CommandOutcome verifyEnvironment(UUID generatorId, String runId, long fencingToken) {
        return send(generatorId, runId, fencingToken, "VerifyEnvironment", b -> b.setVerifyEnvironment(
                VerifyEnvironmentCmd.newBuilder().setExpectedJava("21").setExpectedJmeter("").build()), 60);
    }

    public CommandOutcome startServer(UUID generatorId, String runId, long fencingToken, String workspace,
                                      int rmiPort, int localPort, List<String> classpath) {
        return send(generatorId, runId, fencingToken, "StartJMeterServer", b -> b.setStartJmeterServer(
                StartJMeterServerCmd.newBuilder()
                        .setWorkspacePath(workspace)
                        .setRmiPort(rmiPort)
                        .setLocalPort(localPort)
                        .addAllExtraClasspath(classpath)
                        .build()), 120,
                Map.of("workspace", workspace, "rmiPort", rmiPort, "localPort", localPort));
    }

    public CommandOutcome startTest(UUID generatorId, String runId, long fencingToken, String workspace,
                                    String jmxPath, List<String> remoteHosts, Map<String, String> properties, int rmiPort) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("workspace", workspace);
        extra.put("jmxPath", jmxPath);
        extra.put("remoteHosts", remoteHosts);
        extra.put("propertyCount", properties != null ? properties.size() : 0);
        extra.put("properties", properties != null ? properties : Map.of());
        extra.put("rmiPort", rmiPort);
        return send(generatorId, runId, fencingToken, "StartJMeterTest", b -> b.setStartJmeterTest(
                StartJMeterTestCmd.newBuilder()
                        .setWorkspacePath(workspace)
                        .setJmxPath(jmxPath)
                        .addAllRemoteHosts(remoteHosts)
                        .putAllProperties(properties)
                        .setRmiPort(rmiPort)
                        .build()), 120, extra);
    }

    public CommandOutcome stop(UUID generatorId, String runId, long fencingToken, boolean force) {
        return send(generatorId, runId, fencingToken, "StopJMeter", b -> b.setStopJmeter(
                StopJMeterCmd.newBuilder().setForce(force).build()), 60);
    }

    public CommandOutcome cleanup(UUID generatorId, String runId, long fencingToken, String workspace, boolean keepResults) {
        return send(generatorId, runId, fencingToken, "CleanupExecution", b -> b.setCleanupExecution(
                CleanupExecutionCmd.newBuilder()
                        .setRunId(runId)
                        .setWorkspacePath(workspace)
                        .setKeepResults(keepResults)
                        .build()), 60);
    }

    public CommandOutcome getExecutionStatus(UUID generatorId, String runId, long fencingToken) {
        return send(generatorId, runId, fencingToken, "GetExecutionStatus", b -> b.setGetExecutionStatus(
                GetExecutionStatusCmd.newBuilder().setRunId(runId).build()), 60);
    }

    public void requestLogStream(UUID generatorId, String runId, long fencingToken, long fromOffset) {
        requestLogStream(generatorId, runId, fencingToken, fromOffset, "jmeter.log");
    }

    public void requestLogStream(UUID generatorId, String runId, long fencingToken, long fromOffset, String logFile) {
        String file = logFile == null || logFile.isBlank() ? "jmeter.log" : logFile;
        String commandId = UUID.randomUUID().toString();
        CommandEnvelope envelope = CommandEnvelope.newBuilder()
                .setCommandId(commandId)
                .setRunId(runId)
                .setGeneratorId(generatorId.toString())
                .setFencingToken(fencingToken)
                .setStreamLogs(StreamLogsCmd.newBuilder()
                        .setRunId(runId)
                        .setFromOffset(fromOffset)
                        .setLogFile(file)
                        .build())
                .build();
        genLogs.append(generatorId, "AGENT_CMD", "INFO", "StreamLogs",
                "→ StreamLogs commandId=" + shortId(commandId) + " runId=" + shortId(runId) + " file=" + file,
                Map.of("commandId", commandId, "runId", runId, "logFile", file, "fromOffset", fromOffset));
        sessions.send(generatorId, ControllerMessage.newBuilder().setCommand(envelope).build());
    }

    public static ArtifactFile artifact(String relativePath, String sha256, byte[] content) {
        return ArtifactFile.newBuilder()
                .setRelativePath(relativePath)
                .setSha256(sha256)
                .setContent(ByteString.copyFrom(content))
                .build();
    }

    /**
     * Disconnect the agent so it reconnects and adopts the controller fencing token from RegisterResponse.
     */
    public void resyncFencing(UUID generatorId) throws InterruptedException {
        genLogs.warn(generatorId, "AGENT_CMD", "FENCING_RESYNC",
                "Disconnecting agent to re-adopt controller fencing token");
        sessions.disconnect(generatorId);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (sessions.isOnline(generatorId)) {
                Thread.sleep(750);
                if (sessions.isOnline(generatorId)) {
                    genLogs.info(generatorId, "AGENT_CMD", "FENCING_RESYNC",
                            "Agent reconnected after fencing resync");
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Agent did not reconnect within 60s after fencing disconnect");
    }

    private CommandOutcome send(UUID generatorId, String runId, long fencingToken, String commandName,
                                Consumer<CommandEnvelope.Builder> configurator, long timeout) {
        return send(generatorId, runId, fencingToken, commandName, configurator, timeout, Map.of());
    }

    private CommandOutcome send(UUID generatorId, String runId, long fencingToken, String commandName,
                                Consumer<CommandEnvelope.Builder> configurator, long timeout,
                                Map<String, Object> extraDetail) {
        CommandOutcome outcome = sendOnce(generatorId, runId, fencingToken, commandName, configurator, timeout, extraDetail);
        if (outcome.success() || !isStaleFencing(outcome.message())) {
            return outcome;
        }
        genLogs.warn(generatorId, "AGENT_CMD", "FENCING_RESYNC",
                "Stale fencing on " + commandName + ": " + truncate(outcome.message(), 300)
                        + " — resyncing agent session and retrying once");
        try {
            resyncFencing(generatorId);
        } catch (Exception e) {
            genLogs.error(generatorId, "AGENT_CMD", "FENCING_RESYNC",
                    "Resync failed: " + e.getMessage());
            return new CommandOutcome(false,
                    outcome.message() + "; fencing resync failed: " + e.getMessage(),
                    outcome.attributes() != null ? outcome.attributes() : Map.of());
        }
        return sendOnce(generatorId, runId, fencingToken, commandName, configurator, timeout, extraDetail);
    }

    private CommandOutcome sendOnce(UUID generatorId, String runId, long fencingToken, String commandName,
                                    Consumer<CommandEnvelope.Builder> configurator, long timeout,
                                    Map<String, Object> extraDetail) {
        String commandId = UUID.randomUUID().toString();
        CommandEnvelope.Builder builder = CommandEnvelope.newBuilder()
                .setCommandId(commandId)
                .setRunId(runId != null ? runId : "")
                .setGeneratorId(generatorId.toString())
                .setFencingToken(fencingToken);
        configurator.accept(builder);
        CommandEnvelope envelope = builder.build();

        Map<String, Object> sentDetail = new LinkedHashMap<>();
        sentDetail.put("commandId", commandId);
        sentDetail.put("runId", runId);
        sentDetail.put("fencingToken", fencingToken);
        sentDetail.put("timeoutSec", timeout);
        sentDetail.put("commandCase", envelope.getCommandCase().name());
        if (extraDetail != null) {
            sentDetail.putAll(extraDetail);
        }

        if (!sessions.isOnline(generatorId)) {
            genLogs.error(generatorId, "AGENT_CMD", commandName,
                    "✗ Agent offline — cannot send " + commandName);
            return new CommandOutcome(false, "Agent offline for generator " + generatorId, Map.of());
        }

        String summaryExtra = summarizeExtra(commandName, extraDetail);
        genLogs.append(generatorId, "AGENT_CMD", "INFO", commandName,
                "→ " + commandName + " commandId=" + shortId(commandId)
                        + (runId != null && !runId.isBlank() ? " runId=" + shortId(runId) : "")
                        + " fencing=" + fencingToken
                        + summaryExtra,
                sentDetail);

        CompletableFuture<CommandOutcome> future = sessions.awaitResult(commandId, timeout);
        try {
            sessions.send(generatorId, ControllerMessage.newBuilder().setCommand(envelope).build());
        } catch (Exception e) {
            genLogs.error(generatorId, "AGENT_CMD", commandName, "✗ Send failed: " + e.getMessage());
            return new CommandOutcome(false, e.getMessage(), Map.of());
        }

        try {
            CommandOutcome outcome = future.join();
            Map<String, Object> resultDetail = new LinkedHashMap<>();
            resultDetail.put("commandId", commandId);
            resultDetail.put("success", outcome.success());
            resultDetail.put("message", outcome.message());
            resultDetail.put("attributes", outcome.attributes());
            String attrSummary = summarizeAttributes(outcome.attributes());
            genLogs.append(generatorId, "AGENT_CMD", outcome.success() ? "INFO" : "ERROR", commandName + "_RESULT",
                    (outcome.success() ? "← OK " : "← FAIL ") + commandName
                            + (outcome.message() != null && !outcome.message().isBlank()
                            ? ": " + truncate(outcome.message(), 400) : "")
                            + attrSummary,
                    resultDetail);
            return outcome;
        } catch (Exception e) {
            genLogs.error(generatorId, "AGENT_CMD", commandName + "_RESULT",
                    "← TIMEOUT/ERROR " + commandName + ": " + e.getMessage());
            return new CommandOutcome(false, e.getMessage(), Map.of());
        }
    }

    private static boolean isStaleFencing(String message) {
        return message != null && message.toLowerCase().contains("stale fencing");
    }

    private static String summarizeExtra(String commandName, Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return "";
        return switch (commandName) {
            case "SyncArtifacts" -> " files=" + extra.getOrDefault("fileCount", "?")
                    + " bytes=" + extra.getOrDefault("totalBytes", "?");
            case "StartJMeterTest" -> " jmx=" + extra.getOrDefault("jmxPath", "?")
                    + " remotes=" + extra.getOrDefault("remoteHosts", List.of());
            case "StartJMeterServer" -> " rmi=" + extra.getOrDefault("rmiPort", "?");
            default -> "";
        };
    }

    private static String summarizeAttributes(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("pid", "state", "exitCode", "jtlSamples", "jtlBytes", "jmxPath", "workspace", "argv")) {
            if (attrs.containsKey(key) && attrs.get(key) != null && !attrs.get(key).isBlank()) {
                String val = attrs.get(key);
                if ("argv".equals(key) || val.length() > 120) {
                    val = truncate(val, 120);
                }
                sb.append(' ').append(key).append('=').append(val);
            }
        }
        return sb.toString();
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
