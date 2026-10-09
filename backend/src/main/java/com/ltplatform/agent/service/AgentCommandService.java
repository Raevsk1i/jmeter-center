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
        return send(generatorId, runId, fencingToken, "SyncArtifacts", b -> b.setSyncArtifacts(
                SyncArtifactsCmd.newBuilder().setWorkspacePath(workspace).addAllFiles(files).build()), 120);
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
                        .build()), 120);
    }

    public CommandOutcome startTest(UUID generatorId, String runId, long fencingToken, String workspace,
                                    String jmxPath, List<String> remoteHosts, Map<String, String> properties, int rmiPort) {
        return send(generatorId, runId, fencingToken, "StartJMeterTest", b -> b.setStartJmeterTest(
                StartJMeterTestCmd.newBuilder()
                        .setWorkspacePath(workspace)
                        .setJmxPath(jmxPath)
                        .addAllRemoteHosts(remoteHosts)
                        .putAllProperties(properties)
                        .setRmiPort(rmiPort)
                        .build()), 120);
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
        String commandId = UUID.randomUUID().toString();
        CommandEnvelope envelope = CommandEnvelope.newBuilder()
                .setCommandId(commandId)
                .setRunId(runId)
                .setGeneratorId(generatorId.toString())
                .setFencingToken(fencingToken)
                .setStreamLogs(StreamLogsCmd.newBuilder()
                        .setRunId(runId)
                        .setFromOffset(fromOffset)
                        .setLogFile("jmeter.log")
                        .build())
                .build();
        genLogs.info(generatorId, "AGENT_CMD", "StreamLogs",
                "→ StreamLogs commandId=" + commandId + " runId=" + runId);
        sessions.send(generatorId, ControllerMessage.newBuilder().setCommand(envelope).build());
    }

    public static ArtifactFile artifact(String relativePath, String sha256, byte[] content) {
        return ArtifactFile.newBuilder()
                .setRelativePath(relativePath)
                .setSha256(sha256)
                .setContent(ByteString.copyFrom(content))
                .build();
    }

    private CommandOutcome send(UUID generatorId, String runId, long fencingToken, String commandName,
                                Consumer<CommandEnvelope.Builder> configurator, long timeout) {
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

        if (!sessions.isOnline(generatorId)) {
            genLogs.error(generatorId, "AGENT_CMD", commandName,
                    "✗ Agent offline — cannot send " + commandName);
            return new CommandOutcome(false, "Agent offline for generator " + generatorId, Map.of());
        }

        genLogs.append(generatorId, "AGENT_CMD", "INFO", commandName,
                "→ " + commandName + " commandId=" + shortId(commandId)
                        + (runId != null && !runId.isBlank() ? " runId=" + shortId(runId) : "")
                        + " fencing=" + fencingToken,
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
            resultDetail.put("attributes", outcome.attributes());
            genLogs.append(generatorId, "AGENT_CMD", outcome.success() ? "INFO" : "ERROR", commandName + "_RESULT",
                    (outcome.success() ? "← OK " : "← FAIL ") + commandName
                            + (outcome.message() != null && !outcome.message().isBlank()
                            ? ": " + truncate(outcome.message(), 400) : ""),
                    resultDetail);
            return outcome;
        } catch (Exception e) {
            genLogs.error(generatorId, "AGENT_CMD", commandName + "_RESULT",
                    "← TIMEOUT/ERROR " + commandName + ": " + e.getMessage());
            return new CommandOutcome(false, e.getMessage(), Map.of());
        }
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
