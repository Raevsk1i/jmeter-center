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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AgentCommandService {
    private final AgentSessionRegistry sessions;

    public AgentCommandService(AgentSessionRegistry sessions) {
        this.sessions = sessions;
    }

    public CommandOutcome prepareWorkspace(UUID generatorId, String runId, long fencingToken, String workspace) {
        return send(generatorId, runId, fencingToken, b -> b.setPrepareWorkspace(
                PrepareWorkspaceCmd.newBuilder().setWorkspacePath(workspace).build()), 60);
    }

    public CommandOutcome syncArtifacts(UUID generatorId, String runId, long fencingToken, String workspace,
                                        List<ArtifactFile> files) {
        return send(generatorId, runId, fencingToken, b -> b.setSyncArtifacts(
                SyncArtifactsCmd.newBuilder().setWorkspacePath(workspace).addAllFiles(files).build()), 120);
    }

    public CommandOutcome verifyEnvironment(UUID generatorId, String runId, long fencingToken) {
        return send(generatorId, runId, fencingToken, b -> b.setVerifyEnvironment(
                VerifyEnvironmentCmd.newBuilder().setExpectedJava("21").setExpectedJmeter("").build()), 60);
    }

    public CommandOutcome startServer(UUID generatorId, String runId, long fencingToken, String workspace,
                                      int rmiPort, int localPort, List<String> classpath) {
        return send(generatorId, runId, fencingToken, b -> b.setStartJmeterServer(
                StartJMeterServerCmd.newBuilder()
                        .setWorkspacePath(workspace)
                        .setRmiPort(rmiPort)
                        .setLocalPort(localPort)
                        .addAllExtraClasspath(classpath)
                        .build()), 120);
    }

    public CommandOutcome startTest(UUID generatorId, String runId, long fencingToken, String workspace,
                                    String jmxPath, List<String> remoteHosts, Map<String, String> properties, int rmiPort) {
        return send(generatorId, runId, fencingToken, b -> b.setStartJmeterTest(
                StartJMeterTestCmd.newBuilder()
                        .setWorkspacePath(workspace)
                        .setJmxPath(jmxPath)
                        .addAllRemoteHosts(remoteHosts)
                        .putAllProperties(properties)
                        .setRmiPort(rmiPort)
                        .build()), 120);
    }

    public CommandOutcome stop(UUID generatorId, String runId, long fencingToken, boolean force) {
        return send(generatorId, runId, fencingToken, b -> b.setStopJmeter(
                StopJMeterCmd.newBuilder().setForce(force).build()), 60);
    }

    public CommandOutcome cleanup(UUID generatorId, String runId, long fencingToken, String workspace, boolean keepResults) {
        return send(generatorId, runId, fencingToken, b -> b.setCleanupExecution(
                CleanupExecutionCmd.newBuilder()
                        .setRunId(runId)
                        .setWorkspacePath(workspace)
                        .setKeepResults(keepResults)
                        .build()), 60);
    }

    public CommandOutcome getExecutionStatus(UUID generatorId, String runId, long fencingToken) {
        return send(generatorId, runId, fencingToken, b -> b.setGetExecutionStatus(
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
        sessions.send(generatorId, ControllerMessage.newBuilder().setCommand(envelope).build());
    }

    public static ArtifactFile artifact(String relativePath, String sha256, byte[] content) {
        return ArtifactFile.newBuilder()
                .setRelativePath(relativePath)
                .setSha256(sha256)
                .setContent(ByteString.copyFrom(content))
                .build();
    }

    private CommandOutcome send(UUID generatorId, String runId, long fencingToken,
                                Consumer<CommandEnvelope.Builder> configurator, long timeout) {
        String commandId = UUID.randomUUID().toString();
        CommandEnvelope.Builder builder = CommandEnvelope.newBuilder()
                .setCommandId(commandId)
                .setRunId(runId)
                .setGeneratorId(generatorId.toString())
                .setFencingToken(fencingToken);
        configurator.accept(builder);
        CompletableFuture<CommandOutcome> future = sessions.awaitResult(commandId, timeout);
        sessions.send(generatorId, ControllerMessage.newBuilder().setCommand(builder.build()).build());
        try {
            return future.join();
        } catch (Exception e) {
            return new CommandOutcome(false, e.getMessage(), Map.of());
        }
    }
}
