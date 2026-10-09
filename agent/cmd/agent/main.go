package main

import (
        "log"
        "os"
        "os/signal"
        "syscall"

        "github.com/ltplatform/agent/internal/config"
        "github.com/ltplatform/agent/internal/grpcclient"
        "github.com/ltplatform/agent/internal/recovery"
)

func main() {
        cfg := config.Load()
        log.Printf("lt-agent starting version=%s generator=%s controller=%s",
                cfg.Version, cfg.GeneratorID, cfg.Controller)

        state, err := recovery.Load(cfg.StateDir)
        if err != nil {
                log.Printf("recovery load warning: %v", err)
                state = recovery.NewState()
        }
        state.ReconcileProcesses()

        client := grpcclient.New(cfg, state)
        go func() {
                if err := client.Run(); err != nil {
                        log.Fatalf("agent session failed: %v", err)
                }
        }()

        ch := make(chan os.Signal, 1)
        signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
        <-ch
        log.Printf("shutting down")
        client.Close()
}
