package config

import (
        "os"
)

type Config struct {
        Controller   string
        GeneratorID  string
        AgentID      string
        BootstrapToken string
        Version      string
        WorkRoot     string
        StateDir     string
        JMeterHome   string
        JavaHome     string
}

func Load() Config {
        return Config{
                Controller:     env("LT_CONTROLLER", "localhost:9090"),
                GeneratorID:    env("LT_GENERATOR_ID", ""),
                AgentID:        env("LT_AGENT_ID", "agent-local"),
                BootstrapToken: env("LT_BOOTSTRAP_TOKEN", ""),
                Version:        env("LT_AGENT_VERSION", "0.1.0"),
                WorkRoot:       env("LT_WORK_ROOT", "/var/lib/lt-agent/workspaces"),
                StateDir:       env("LT_STATE_DIR", "/var/lib/lt-agent/state"),
                JMeterHome:     env("LT_JMETER_HOME", "/opt/lt-jmeter"),
                JavaHome:       env("LT_JAVA_HOME", ""),
        }
}

func env(k, def string) string {
        if v := os.Getenv(k); v != "" {
                return v
        }
        return def
}
