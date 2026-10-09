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
	mu           sync.Mutex
	jmeterHome   string
	state        *recovery.State
	cmd          *exec.Cmd
	role         string
	runID        string
	lockFile     *os.File
	lastExitCode int
	finished     bool
	startArgs    []string
	workspace    string
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

func (e *Executor) StartServer(runID, workspace string, rmiPort, localPort int, extraCP []string, stateDir string) (int, []string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil && e.aliveLocked() {
		return 0, nil, fmt.Errorf("jmeter already running pid=%d", e.cmd.Process.Pid)
	}
	if err := e.acquireLock(stateDir); err != nil {
		return 0, nil, err
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
		return 0, nil, err
	}
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	cmd.Env = append(os.Environ(),
		"JVM_ARGS=-Xms256m -Xmx2g",
	)
	if err := cmd.Start(); err != nil {
		e.releaseLock()
		return 0, nil, err
	}
	e.cmd = cmd
	e.role = "SLAVE"
	e.runID = runID
	e.workspace = workspace
	e.startArgs = append([]string{bin}, args...)
	e.lastExitCode = 0
	e.finished = false
	e.state.SetExecution(&recovery.ExecutionState{
		RunID: runID, Role: "SLAVE", PID: cmd.Process.Pid, Workspace: workspace,
		FencingTok: e.state.FencingToken,
	})
	go e.wait(cmd)
	time.Sleep(2 * time.Second)
	return cmd.Process.Pid, e.startArgs, nil
}

func (e *Executor) StartTest(runID, workspace, jmx string, remoteHosts []string, props map[string]string, rmiPort int, stateDir string) (int, []string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil && e.aliveLocked() {
		return 0, nil, fmt.Errorf("jmeter already running pid=%d", e.cmd.Process.Pid)
	}
	jmxPath := filepath.Join(workspace, jmx)
	if st, err := os.Stat(jmxPath); err != nil || st.IsDir() {
		return 0, nil, fmt.Errorf("jmx not found at %s: %v", jmxPath, err)
	}
	if err := e.acquireLock(stateDir); err != nil {
		return 0, nil, err
	}
	bin := filepath.Join(e.jmeterHome, "bin", "jmeter")
	args := []string{"-n", "-t", jmxPath, "-l", filepath.Join(workspace, "results.jtl"),
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
	args = append(args, "-Jdata.dir="+filepath.Join(workspace, "data"))
	cmd := exec.Command(bin, args...)
	cmd.Dir = workspace
	cmd.Env = append(os.Environ(), "JVM_ARGS=-Xms256m -Xmx2g")
	consoleLog, err := os.Create(filepath.Join(workspace, "jmeter-console.log"))
	if err != nil {
		e.releaseLock()
		return 0, nil, err
	}
	cmd.Stdout = consoleLog
	cmd.Stderr = consoleLog
	if err := cmd.Start(); err != nil {
		e.releaseLock()
		return 0, nil, err
	}
	e.cmd = cmd
	e.role = "MASTER"
	e.runID = runID
	e.workspace = workspace
	e.startArgs = append([]string{bin}, args...)
	e.lastExitCode = 0
	e.finished = false
	e.state.SetExecution(&recovery.ExecutionState{
		RunID: runID, Role: "MASTER", PID: cmd.Process.Pid, Workspace: workspace,
		FencingTok: e.state.FencingToken,
	})
	go e.wait(cmd)
	return cmd.Process.Pid, e.startArgs, nil
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

func (e *Executor) Status(runID string) (alive bool, pid int, role string, state string, exitCode int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.runID != "" && runID != "" && e.runID != runID {
		return false, 0, "", "IDLE", 0
	}
	if e.cmd != nil && e.cmd.Process != nil && e.aliveLocked() {
		return true, e.cmd.Process.Pid, e.role, "RUNNING", 0
	}
	if ex := e.state.GetExecution(); ex != nil && (runID == "" || ex.RunID == runID) {
		if err := syscall.Kill(ex.PID, 0); err == nil {
			return true, ex.PID, ex.Role, "RUNNING", 0
		}
	}
	// Only report COMPLETED/FAILED after wait() finished a real process.
	// Previously IDLE (never started) was reported as COMPLETED exit=0 — that made
	// the controller mark runs successful even when JMeter never ran.
	if e.finished && (runID == "" || e.runID == "" || e.runID == runID) {
		st := "COMPLETED"
		if e.lastExitCode != 0 {
			st = "FAILED"
		}
		return false, 0, e.role, st, e.lastExitCode
	}
	return false, 0, e.role, "IDLE", 0
}

func (e *Executor) LastWorkspace() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.workspace
}

func (e *Executor) StartArgs() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]string, len(e.startArgs))
	copy(out, e.startArgs)
	return out
}

func (e *Executor) aliveLocked() bool {
	if e.cmd == nil || e.cmd.Process == nil {
		return false
	}
	return syscall.Kill(e.cmd.Process.Pid, 0) == nil
}

func (e *Executor) wait(cmd *exec.Cmd) {
	err := cmd.Wait()
	code := 0
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			code = ee.ExitCode()
		} else {
			code = -1
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.lastExitCode = code
	e.finished = true
	if e.cmd == cmd {
		e.cmd = nil
		e.state.ClearExecution()
		e.releaseLock()
	}
}
