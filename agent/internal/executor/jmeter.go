package executor

import (
        "fmt"
        "os"
        "os/exec"
        "path/filepath"
        "strings"
        "sync"
        "syscall"
        "time"

        "github.com/ltplatform/agent/internal/recovery"
)

type Executor struct {
        mu         sync.Mutex
        jmeterHome string
        state      *recovery.State
        cmd        *exec.Cmd
        role       string
        runID      string
        lockFile   *os.File
}

func New(jmeterHome string, state *recovery.State) *Executor {
        return &Executor{jmeterHome: jmeterHome, state: state}
}

func (e *Executor) acquireLock(stateDir string) error {
        if err := os.MkdirAll(stateDir, 0o755); err != nil {
                return err
        }
        f, err := os.OpenFile(filepath.Join(stateDir, "exec.lock"), os.O_CREATE|os.O_RDWR, 0o600)
        if err != nil {
                return err
        }
        if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
                _ = f.Close()
                return fmt.Errorf("local execution lock held")
        }
        e.lockFile = f
        return nil
}

func (e *Executor) releaseLock() {
        if e.lockFile != nil {
                _ = syscall.Flock(int(e.lockFile.Fd()), syscall.LOCK_UN)
                _ = e.lockFile.Close()
                e.lockFile = nil
        }
}

func (e *Executor) StartServer(runID, workspace string, rmiPort, localPort int, extraCP []string, stateDir string) error {
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.cmd != nil && e.aliveLocked() {
                return fmt.Errorf("jmeter already running pid=%d", e.cmd.Process.Pid)
        }
        if err := e.acquireLock(stateDir); err != nil {
                return err
        }
        bin := filepath.Join(e.jmeterHome, "bin", "jmeter-server")
        args := []string{
                "-Djava.rmi.server.hostname=0.0.0.0",
                fmt.Sprintf("-Dserver_port=%d", rmiPort),
                fmt.Sprintf("-Dserver.rmi.localport=%d", localPort),
        }
        if len(extraCP) > 0 {
                args = append(args, "-Juser.classpath="+strings.Join(extraCP, ":"))
        }
        libDir := filepath.Join(workspace, "lib")
        if entries, err := os.ReadDir(libDir); err == nil && len(entries) > 0 {
                args = append(args, "-Jsearch_paths="+libDir)
        }
        cmd := exec.Command(bin, args...)
        cmd.Dir = workspace
        logFile, err := os.Create(filepath.Join(workspace, "jmeter.log"))
        if err != nil {
                e.releaseLock()
                return err
        }
        cmd.Stdout = logFile
        cmd.Stderr = logFile
        cmd.Env = append(os.Environ(),
                "JVM_ARGS=-Xms256m -Xmx2g",
        )
        if err := cmd.Start(); err != nil {
                e.releaseLock()
                return err
        }
        e.cmd = cmd
        e.role = "SLAVE"
        e.runID = runID
        e.state.SetExecution(&recovery.ExecutionState{
                RunID: runID, Role: "SLAVE", PID: cmd.Process.Pid, Workspace: workspace,
                FencingTok: e.state.FencingToken,
        })
        go e.wait(cmd)
        // brief wait for RMI bind
        time.Sleep(2 * time.Second)
        return nil
}

func (e *Executor) StartTest(runID, workspace, jmx string, remoteHosts []string, props map[string]string, rmiPort int, stateDir string) error {
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.cmd != nil && e.aliveLocked() {
                return fmt.Errorf("jmeter already running pid=%d", e.cmd.Process.Pid)
        }
        if err := e.acquireLock(stateDir); err != nil {
                return err
        }
        bin := filepath.Join(e.jmeterHome, "bin", "jmeter")
        args := []string{"-n", "-t", filepath.Join(workspace, jmx), "-l", filepath.Join(workspace, "results.jtl"),
                "-j", filepath.Join(workspace, "jmeter.log")}
        if len(remoteHosts) > 0 {
                args = append(args, "-R", strings.Join(remoteHosts, ","))
                args = append(args, fmt.Sprintf("-Dclient.rmi.localport=%d", rmiPort+1))
        }
        libDir := filepath.Join(workspace, "lib")
        if entries, err := os.ReadDir(libDir); err == nil && len(entries) > 0 {
                args = append(args, "-Jsearch_paths="+libDir)
        }
        for k, v := range props {
                args = append(args, "-J"+k+"="+v)
        }
        // point relative data paths into workspace/data
        args = append(args, "-Jdata.dir="+filepath.Join(workspace, "data"))
        cmd := exec.Command(bin, args...)
        cmd.Dir = workspace
        cmd.Env = append(os.Environ(), "JVM_ARGS=-Xms256m -Xmx2g")
        if err := cmd.Start(); err != nil {
                e.releaseLock()
                return err
        }
        e.cmd = cmd
        e.role = "MASTER"
        e.runID = runID
        e.state.SetExecution(&recovery.ExecutionState{
                RunID: runID, Role: "MASTER", PID: cmd.Process.Pid, Workspace: workspace,
                FencingTok: e.state.FencingToken,
        })
        go e.wait(cmd)
        return nil
}

func (e *Executor) Stop(force bool) error {
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.cmd == nil || e.cmd.Process == nil {
                return nil
        }
        if force {
                _ = e.cmd.Process.Kill()
        } else {
                _ = e.cmd.Process.Signal(syscall.SIGTERM)
                done := make(chan struct{})
                go func() {
                        _, _ = e.cmd.Process.Wait()
                        close(done)
                }()
                // Process.Wait used for graceful stop wait only
                select {
                case <-done:
                case <-time.After(15 * time.Second):
                        _ = e.cmd.Process.Kill()
                }
        }
        e.cmd = nil
        e.state.ClearExecution()
        e.releaseLock()
        return nil
}

func (e *Executor) Status(runID string) (alive bool, pid int, role string, state string) {
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.runID != "" && runID != "" && e.runID != runID {
                return false, 0, "", "IDLE"
        }
        if e.cmd != nil && e.cmd.Process != nil && e.aliveLocked() {
                return true, e.cmd.Process.Pid, e.role, "RUNNING"
        }
        if ex := e.state.GetExecution(); ex != nil && (runID == "" || ex.RunID == runID) {
                if err := syscall.Kill(ex.PID, 0); err == nil {
                        return true, ex.PID, ex.Role, "RUNNING"
                }
        }
        return false, 0, e.role, "COMPLETED"
}

func (e *Executor) aliveLocked() bool {
        if e.cmd == nil || e.cmd.Process == nil {
                return false
        }
        return syscall.Kill(e.cmd.Process.Pid, 0) == nil
}

func (e *Executor) wait(cmd *exec.Cmd) {
        _ = cmd.Wait()
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.cmd == cmd {
                e.cmd = nil
                e.state.ClearExecution()
                e.releaseLock()
        }
}
