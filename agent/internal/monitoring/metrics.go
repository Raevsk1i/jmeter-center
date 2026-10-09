package monitoring

import (
        "os"
        "os/exec"
        "runtime"
        "strconv"
        "strings"
)

type Metrics struct {
        CPUPercent float64
        RAMUsedMB  int64
        RAMTotalMB int64
        DiskFreeMB int64
        JavaVer    string
        JMeterVer  string
}

func Collect(jmeterHome string) Metrics {
        m := Metrics{}
        m.CPUPercent = float64(runtime.NumCPU())
        // rough memory via /proc/meminfo
        if b, err := os.ReadFile("/proc/meminfo"); err == nil {
                lines := strings.Split(string(b), "\n")
                var total, available int64
                for _, line := range lines {
                        fields := strings.Fields(line)
                        if len(fields) < 2 {
                                continue
                        }
                        val, _ := strconv.ParseInt(fields[1], 10, 64)
                        switch fields[0] {
                        case "MemTotal:":
                                total = val / 1024
                        case "MemAvailable:":
                                available = val / 1024
                        }
                }
                m.RAMTotalMB = total
                m.RAMUsedMB = total - available
        }
        if out, err := exec.Command("df", "-Pm", "/").Output(); err == nil {
                lines := strings.Split(strings.TrimSpace(string(out)), "\n")
                if len(lines) >= 2 {
                        fields := strings.Fields(lines[1])
                        if len(fields) >= 4 {
                                free, _ := strconv.ParseInt(fields[3], 10, 64)
                                m.DiskFreeMB = free
                        }
                }
        }
        if out, err := exec.Command("java", "-version").CombinedOutput(); err == nil {
                m.JavaVer = clip(preferLine(string(out), "version"), 500)
        }
        jmeter := jmeterHome + "/bin/jmeter"
        if out, err := exec.Command(jmeter, "-v").CombinedOutput(); err == nil {
                // jmeter -v often prints ASCII art first; prefer a line with "JMeter" / "Version"
                m.JMeterVer = clip(preferLine(string(out), "JMeter", "Version", "version"), 500)
        }
        return m
}

func preferLine(s string, needles ...string) string {
        lines := strings.Split(s, "\n")
        for _, line := range lines {
                trimmed := strings.TrimSpace(line)
                if trimmed == "" {
                        continue
                }
                for _, n := range needles {
                        if strings.Contains(trimmed, n) {
                                return trimmed
                        }
                }
        }
        for _, line := range lines {
                if trimmed := strings.TrimSpace(line); trimmed != "" {
                        return trimmed
                }
        }
        return strings.TrimSpace(s)
}

func clip(s string, max int) string {
        s = strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(s, "\r", " "), "\n", " "))
        if len(s) <= max {
                return s
        }
        return s[:max]
}
