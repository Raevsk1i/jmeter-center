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
                m.JavaVer = firstLine(string(out))
        }
        jmeter := jmeterHome + "/bin/jmeter"
        if out, err := exec.Command(jmeter, "-v").CombinedOutput(); err == nil {
                m.JMeterVer = firstLine(string(out))
        }
        return m
}

func firstLine(s string) string {
        if i := strings.IndexByte(s, '\n'); i >= 0 {
                return strings.TrimSpace(s[:i])
        }
        return strings.TrimSpace(s)
}
