package grpcclient

import (
        "context"
        "fmt"
        "io"
        "log"
        "os"
        "path/filepath"
        "strconv"
        "strings"
        "sync"
        "time"

        agentv1 "github.com/ltplatform/agent/api/v1"
        "github.com/ltplatform/agent/internal/artifacts"
        "github.com/ltplatform/agent/internal/config"
        "github.com/ltplatform/agent/internal/executor"
        agentlog "github.com/ltplatform/agent/internal/logging"
        "github.com/ltplatform/agent/internal/monitoring"
        "github.com/ltplatform/agent/internal/recovery"
        "google.golang.org/grpc"
        "google.golang.org/grpc/credentials/insecure"
)

type Client struct {
        cfg    config.Config
        state  *recovery.State
        exec   *executor.Executor
        cancel context.CancelFunc
        mu     sync.Mutex
        stream agentv1.AgentControl_AgentSessionClient
}

func New(cfg config.Config, state *recovery.State) *Client {
        return &Client{
                cfg:   cfg,
                state: state,
                exec:  executor.New(cfg.JMeterHome, state),
        }
}

func (c *Client) Run() error {
        for {
                if err := c.session(); err != nil {
                        log.Printf("session ended: %v; reconnecting in 5s", err)
                        time.Sleep(5 * time.Second)
                        continue
                }
                return nil
        }
}

func (c *Client) Close() {
        if c.cancel != nil {
                c.cancel()
        }
}

func (c *Client) session() error {
        ctx, cancel := context.WithCancel(context.Background())
        c.cancel = cancel
        conn, err := grpc.NewClient(c.cfg.Controller, grpc.WithTransportCredentials(insecure.NewCredentials()),
                grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(64<<20), grpc.MaxCallSendMsgSize(64<<20)))
        if err != nil {
                return err
        }
        defer conn.Close()

        client := agentv1.NewAgentControlClient(conn)
        stream, err := client.AgentSession(ctx)
        if err != nil {
                return err
        }
        c.mu.Lock()
        c.stream = stream
        c.mu.Unlock()

        m := monitoring.Collect(c.cfg.JMeterHome)
        if err := stream.Send(&agentv1.AgentMessage{Payload: &agentv1.AgentMessage_Register{Register: &agentv1.RegisterRequest{
                AgentId:        c.cfg.AgentID,
                Hostname:       hostname(),
                BootstrapToken: c.cfg.BootstrapToken,
                AgentVersion:   c.cfg.Version,
                JavaVersion:    m.JavaVer,
                JmeterVersion:  m.JMeterVer,
                GeneratorId:    c.cfg.GeneratorID,
        }}}); err != nil {
                return err
        }

        // wait register response
        msg, err := stream.Recv()
        if err != nil {
                return err
        }
        reg := msg.GetRegisterResponse()
        if reg == nil || !reg.Accepted {
                return fmt.Errorf("registration rejected: %v", reg)
        }
        prevFence := c.state.AdoptFencing(reg.FencingToken)
        if prevFence > reg.FencingToken {
                log.Printf("registered session=%s fencing=%d (adopted controller token; local was higher: %d)",
                        reg.SessionId, reg.FencingToken, prevFence)
        } else {
                log.Printf("registered session=%s fencing=%d", reg.SessionId, reg.FencingToken)
        }

        // write issued certs if present
        if len(reg.ClientCertPem) > 0 {
                certDir := filepath.Join(c.cfg.StateDir, "certs")
                _ = os.MkdirAll(certDir, 0o700)
                _ = os.WriteFile(filepath.Join(certDir, "client.crt"), reg.ClientCertPem, 0o600)
                _ = os.WriteFile(filepath.Join(certDir, "client.key"), reg.ClientKeyPem, 0o600)
        }

        errCh := make(chan error, 2)
        go func() { errCh <- c.heartbeatLoop(ctx, stream, reg.SessionId) }()
        go func() { errCh <- c.recvLoop(stream) }()
        return <-errCh
}

func (c *Client) heartbeatLoop(ctx context.Context, stream agentv1.AgentControl_AgentSessionClient, sessionID string) error {
        t := time.NewTicker(10 * time.Second)
        defer t.Stop()
        for {
                select {
                case <-ctx.Done():
                        return ctx.Err()
                case <-t.C:
                        m := monitoring.Collect(c.cfg.JMeterHome)
                        alive, _, _, _, _ := c.exec.Status("")
                        status := "AVAILABLE"
                        var runID *string
                        if alive {
                                status = "RUNNING"
                                if ex := c.state.GetExecution(); ex != nil {
                                        runID = &ex.RunID
                                }
                        }
                        hb := &agentv1.Heartbeat{
                                AgentId:          c.cfg.AgentID,
                                SessionId:        sessionID,
                                CpuUsagePercent:  m.CPUPercent,
                                RamUsedMb:        m.RAMUsedMB,
                                RamTotalMb:       m.RAMTotalMB,
                                DiskFreeMb:       m.DiskFreeMB,
                                Status:           status,
                        }
                        if runID != nil {
                                hb.ActiveRunId = runID
                        }
                        if err := stream.Send(&agentv1.AgentMessage{Payload: &agentv1.AgentMessage_Heartbeat{Heartbeat: hb}}); err != nil {
                                return err
                        }
                }
        }
}

func (c *Client) recvLoop(stream agentv1.AgentControl_AgentSessionClient) error {
        for {
                msg, err := stream.Recv()
                if err == io.EOF {
                        return err
                }
                if err != nil {
                        return err
                }
                cmd := msg.GetCommand()
                if cmd == nil {
                        continue
                }
                go c.handleCommand(stream, cmd)
        }
}

func (c *Client) handleCommand(stream agentv1.AgentControl_AgentSessionClient, cmd *agentv1.CommandEnvelope) {
        result := &agentv1.CommandResult{CommandId: cmd.CommandId, RunId: cmd.RunId, Success: true, Attributes: map[string]string{}}
        defer func() {
                if err := stream.Send(&agentv1.AgentMessage{Payload: &agentv1.AgentMessage_CommandResult{CommandResult: result}}); err != nil {
                        log.Printf("failed to send command result: %v", err)
                }
        }()

        if c.state.SeenCommand(cmd.CommandId) {
                result.Message = "duplicate command ignored"
                return
        }
        if !c.state.AcceptFencing(cmd.FencingToken) {
                result.Success = false
                result.Message = fmt.Sprintf("stale fencing token: command=%d local=%d",
                        cmd.FencingToken, c.state.CurrentFencing())
                result.Attributes["commandFencingToken"] = strconv.FormatInt(cmd.FencingToken, 10)
                result.Attributes["localFencingToken"] = strconv.FormatInt(c.state.CurrentFencing(), 10)
                log.Printf("rejecting command %s: %s", cmd.CommandId, result.Message)
                return
        }

        var err error
        switch x := cmd.Command.(type) {
        case *agentv1.CommandEnvelope_GetAgentStatus:
                alive, pid, role, state, exitCode := c.exec.Status(cmd.RunId)
                result.Attributes["jmeterAlive"] = strconv.FormatBool(alive)
                result.Attributes["pid"] = strconv.Itoa(pid)
                result.Attributes["role"] = role
                result.Attributes["state"] = state
                result.Attributes["exitCode"] = strconv.Itoa(exitCode)
                result.Message = "ok"
        case *agentv1.CommandEnvelope_GetSystemMetrics:
                m := monitoring.Collect(c.cfg.JMeterHome)
                result.Attributes["cpu"] = fmt.Sprintf("%f", m.CPUPercent)
                result.Attributes["ramUsed"] = strconv.FormatInt(m.RAMUsedMB, 10)
                result.Attributes["diskFree"] = strconv.FormatInt(m.DiskFreeMB, 10)
                result.Message = "ok"
        case *agentv1.CommandEnvelope_PrepareWorkspace:
                ws := c.workspace(cmd.RunId, x.PrepareWorkspace.WorkspacePath)
                err = artifacts.PrepareWorkspace(ws)
                result.Attributes["workspace"] = ws
        case *agentv1.CommandEnvelope_SyncArtifacts:
                ws := c.workspace(cmd.RunId, x.SyncArtifacts.WorkspacePath)
                files := make([]artifacts.File, 0, len(x.SyncArtifacts.Files))
                names := make([]string, 0, len(x.SyncArtifacts.Files))
                var totalBytes int
                for _, f := range x.SyncArtifacts.Files {
                        files = append(files, artifacts.File{RelativePath: f.RelativePath, SHA256: f.Sha256, Content: f.Content})
                        names = append(names, f.RelativePath)
                        totalBytes += len(f.Content)
                }
                err = artifacts.Sync(ws, files)
                result.Attributes["workspace"] = ws
                result.Attributes["fileCount"] = strconv.Itoa(len(files))
                result.Attributes["totalBytes"] = strconv.Itoa(totalBytes)
                if len(names) > 0 {
                        result.Attributes["files"] = strings.Join(names, ",")
                }
                result.Message = fmt.Sprintf("synced %d files (%d bytes) to %s", len(files), totalBytes, ws)
        case *agentv1.CommandEnvelope_VerifyEnvironment:
                m := monitoring.Collect(c.cfg.JMeterHome)
                if m.JavaVer == "" {
                        err = fmt.Errorf("java not found")
                } else {
                        result.Attributes["java"] = m.JavaVer
                        result.Attributes["jmeter"] = m.JMeterVer
                        result.Attributes["jmeterHome"] = c.cfg.JMeterHome
                        result.Message = "environment ok"
                }
        case *agentv1.CommandEnvelope_StartJmeterServer:
                ws := c.workspace(cmd.RunId, x.StartJmeterServer.WorkspacePath)
                var pid int
                var argv []string
                pid, argv, err = c.exec.StartServer(cmd.RunId, ws,
                        int(x.StartJmeterServer.RmiPort), int(x.StartJmeterServer.LocalPort),
                        x.StartJmeterServer.ExtraClasspath, c.cfg.StateDir)
                if err == nil {
                        result.Attributes["pid"] = strconv.Itoa(pid)
                        result.Attributes["workspace"] = ws
                        result.Attributes["rmiPort"] = strconv.Itoa(int(x.StartJmeterServer.RmiPort))
                        result.Attributes["argv"] = strings.Join(argv, " ")
                        result.Message = fmt.Sprintf("jmeter-server started pid=%d", pid)
                }
        case *agentv1.CommandEnvelope_StartJmeterTest:
                ws := c.workspace(cmd.RunId, x.StartJmeterTest.WorkspacePath)
                var pid int
                var argv []string
                pid, argv, err = c.exec.StartTest(cmd.RunId, ws, x.StartJmeterTest.JmxPath,
                        x.StartJmeterTest.RemoteHosts, x.StartJmeterTest.Properties, int(x.StartJmeterTest.RmiPort), c.cfg.StateDir)
                if err == nil {
                        result.Attributes["pid"] = strconv.Itoa(pid)
                        result.Attributes["workspace"] = ws
                        result.Attributes["jmxPath"] = filepath.Join(ws, x.StartJmeterTest.JmxPath)
                        result.Attributes["remoteHosts"] = strings.Join(x.StartJmeterTest.RemoteHosts, ",")
                        result.Attributes["propertyCount"] = strconv.Itoa(len(x.StartJmeterTest.Properties))
                        result.Attributes["argv"] = strings.Join(argv, " ")
                        result.Message = fmt.Sprintf("jmeter started pid=%d jmx=%s", pid, x.StartJmeterTest.JmxPath)
                        log.Printf("StartJMeterTest pid=%d argv=%v", pid, argv)
                }
        case *agentv1.CommandEnvelope_StopJmeter:
                err = c.exec.Stop(x.StopJmeter.Force)
        case *agentv1.CommandEnvelope_GetExecutionStatus:
                alive, pid, role, state, exitCode := c.exec.Status(x.GetExecutionStatus.RunId)
                result.Attributes["jmeterAlive"] = strconv.FormatBool(alive)
                result.Attributes["pid"] = strconv.Itoa(pid)
                result.Attributes["role"] = role
                result.Attributes["state"] = state
                result.Attributes["exitCode"] = strconv.Itoa(exitCode)
                ws := c.workspace(x.GetExecutionStatus.RunId, "")
                result.Attributes["workspace"] = ws
                jtlPath := filepath.Join(ws, "results.jtl")
                samples, jtlBytes, jtlErr := countJtlSamples(jtlPath)
                result.Attributes["jtlSamples"] = strconv.Itoa(samples)
                result.Attributes["jtlBytes"] = strconv.FormatInt(jtlBytes, 10)
                if jtlErr != nil {
                        result.Attributes["jtlError"] = jtlErr.Error()
                }
                result.Attributes["jmeterLogTail"] = tailFile(filepath.Join(ws, "jmeter.log"), 60)
                result.Attributes["consoleLogTail"] = tailFile(filepath.Join(ws, "jmeter-console.log"), 40)
                result.Message = fmt.Sprintf("state=%s alive=%v exit=%d jtlSamples=%d", state, alive, exitCode, samples)
        case *agentv1.CommandEnvelope_StreamLogs:
                go c.streamLogs(stream, cmd.RunId, x.StreamLogs)
                result.Message = "streaming"
        case *agentv1.CommandEnvelope_CleanupExecution:
                _ = c.exec.Stop(true)
                err = artifacts.Cleanup(c.workspace(cmd.RunId, x.CleanupExecution.WorkspacePath), x.CleanupExecution.KeepResults)
        default:
                err = fmt.Errorf("unknown command")
        }
        if err != nil {
                result.Success = false
                result.Message = err.Error()
                return
        }
        c.state.MarkCommand(cmd.CommandId)
        if result.Message == "" {
                result.Message = "ok"
        }
}

func (c *Client) streamLogs(stream agentv1.AgentControl_AgentSessionClient, runID string, req *agentv1.StreamLogsCmd) {
        workspace := filepath.Join(c.cfg.WorkRoot, runID)
        path := filepath.Join(workspace, req.LogFile)
        if req.LogFile == "" {
                path = filepath.Join(workspace, "jmeter.log")
        }
        offset := req.FromOffset
        for i := 0; i < 3600; i++ {
                data, next, eof, err := agentlog.ReadFrom(path, offset, 32*1024)
                if err == nil && len(data) > 0 {
                        _ = stream.Send(&agentv1.AgentMessage{Payload: &agentv1.AgentMessage_LogChunk{LogChunk: &agentv1.LogChunk{
                                RunId: runID, GeneratorId: c.cfg.GeneratorID, LogFile: filepath.Base(path),
                                Offset: offset, Data: data, Eof: eof,
                        }}})
                        offset = next
                }
                if eof {
                        time.Sleep(2 * time.Second)
                } else {
                        time.Sleep(500 * time.Millisecond)
                }
                alive, _, _, _, _ := c.exec.Status(runID)
                if !alive && eof {
                        return
                }
        }
}

func countJtlSamples(path string) (int, int64, error) {
        st, err := os.Stat(path)
        if err != nil {
                return 0, 0, err
        }
        data, err := os.ReadFile(path)
        if err != nil {
                return 0, st.Size(), err
        }
        lines := 0
        for _, line := range strings.Split(string(data), "\n") {
                trimmed := strings.TrimSpace(line)
                if trimmed == "" {
                        continue
                }
                // skip CSV header
                if strings.HasPrefix(trimmed, "timeStamp") || strings.HasPrefix(trimmed, "<?xml") {
                        continue
                }
                lines++
        }
        return lines, st.Size(), nil
}

func tailFile(path string, maxLines int) string {
        data, err := os.ReadFile(path)
        if err != nil {
                return ""
        }
        lines := strings.Split(string(data), "\n")
        if len(lines) > maxLines {
                lines = lines[len(lines)-maxLines:]
        }
        out := strings.Join(lines, "\n")
        if len(out) > 4000 {
                return out[len(out)-4000:]
        }
        return out
}

func (c *Client) workspace(runID, requested string) string {
        // Always isolate under configured work root; ignore absolute controller paths that may not exist locally.
        if runID != "" {
                return filepath.Join(c.cfg.WorkRoot, runID)
        }
        if requested != "" {
                return filepath.Join(c.cfg.WorkRoot, filepath.Base(requested))
        }
        return c.cfg.WorkRoot
}

func hostname() string {
        h, err := os.Hostname()
        if err != nil {
                return "unknown"
        }
        return h
}
