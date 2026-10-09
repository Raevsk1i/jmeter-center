# Agent gRPC Contract

Service: `ltplatform.agent.v1.AgentControl`

## AgentSession (bidi stream)

Agent → Controller:
- `RegisterRequest`
- `Heartbeat`
- `CommandResult`
- `LogChunk`
- `ExecutionStatusReport`
- `SystemMetricsReport`

Controller → Agent:
- `RegisterResponse` (session id, client cert/key, fencing token)
- `CommandEnvelope` (`run_id`, `command_id`, `fencing_token`, oneof command)

Commands: GetAgentStatus, GetSystemMetrics, PrepareWorkspace, SyncArtifacts, VerifyEnvironment, StartJMeterServer, StartJMeterTest, StopJMeter, GetExecutionStatus, StreamLogs, CleanupExecution.
