package recovery

import (
        "encoding/json"
        "os"
        "path/filepath"
        "sync"
        "syscall"
)

type ExecutionState struct {
        RunID      string `json:"runId"`
        Role       string `json:"role"`
        PID        int    `json:"pid"`
        Workspace  string `json:"workspace"`
        FencingTok int64  `json:"fencingToken"`
}

type State struct {
        mu           sync.Mutex
        path         string
        FencingToken int64                    `json:"fencingToken"`
        Commands     map[string]bool          `json:"commands"`
        Execution    *ExecutionState          `json:"execution,omitempty"`
}

func NewState() *State {
        return &State{Commands: map[string]bool{}}
}

func Load(dir string) (*State, error) {
        if err := os.MkdirAll(dir, 0o755); err != nil {
                return nil, err
        }
        path := filepath.Join(dir, "state.json")
        s := &State{path: path, Commands: map[string]bool{}}
        b, err := os.ReadFile(path)
        if err != nil {
                if os.IsNotExist(err) {
                        return s, nil
                }
                return nil, err
        }
        if err := json.Unmarshal(b, s); err != nil {
                return s, err
        }
        s.path = path
        if s.Commands == nil {
                s.Commands = map[string]bool{}
        }
        return s, nil
}

func (s *State) Save() error {
        s.mu.Lock()
        defer s.mu.Unlock()
        b, err := json.MarshalIndent(s, "", "  ")
        if err != nil {
                return err
        }
        return os.WriteFile(s.path, b, 0o600)
}

func (s *State) SeenCommand(id string) bool {
        s.mu.Lock()
        defer s.mu.Unlock()
        return s.Commands[id]
}

func (s *State) MarkCommand(id string) {
        s.mu.Lock()
        defer s.mu.Unlock()
        s.Commands[id] = true
        _ = s.persistLocked()
}

func (s *State) AcceptFencing(token int64) bool {
        s.mu.Lock()
        defer s.mu.Unlock()
        if token < s.FencingToken {
                return false
        }
        s.FencingToken = token
        _ = s.persistLocked()
        return true
}

func (s *State) SetExecution(ex *ExecutionState) {
        s.mu.Lock()
        defer s.mu.Unlock()
        s.Execution = ex
        _ = s.persistLocked()
}

func (s *State) ClearExecution() {
        s.mu.Lock()
        defer s.mu.Unlock()
        s.Execution = nil
        _ = s.persistLocked()
}

func (s *State) GetExecution() *ExecutionState {
        s.mu.Lock()
        defer s.mu.Unlock()
        return s.Execution
}

func (s *State) ReconcileProcesses() {
        s.mu.Lock()
        defer s.mu.Unlock()
        if s.Execution == nil || s.Execution.PID <= 0 {
                return
        }
        if err := syscall.Kill(s.Execution.PID, 0); err != nil {
                s.Execution = nil
                _ = s.persistLocked()
        }
}

func (s *State) persistLocked() error {
        if s.path == "" {
                return nil
        }
        b, err := json.MarshalIndent(s, "", "  ")
        if err != nil {
                return err
        }
        return os.WriteFile(s.path, b, 0o600)
}
