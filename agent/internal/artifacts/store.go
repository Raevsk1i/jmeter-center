package artifacts

import (
        "crypto/sha256"
        "encoding/hex"
        "fmt"
        "os"
        "path/filepath"
)

type File struct {
        RelativePath string
        SHA256       string
        Content      []byte
}

func PrepareWorkspace(path string) error {
        return os.MkdirAll(path, 0o755)
}

func Sync(workspace string, files []File) error {
        for _, f := range files {
                sum := sha256.Sum256(f.Content)
                got := hex.EncodeToString(sum[:])
                if f.SHA256 != "" && got != f.SHA256 {
                        return fmt.Errorf("checksum mismatch for %s", f.RelativePath)
                }
                dest := filepath.Join(workspace, f.RelativePath)
                if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
                        return err
                }
                if err := os.WriteFile(dest, f.Content, 0o644); err != nil {
                        return err
                }
        }
        // ensure lib/ is present for classpath
        _ = os.MkdirAll(filepath.Join(workspace, "lib"), 0o755)
        _ = os.MkdirAll(filepath.Join(workspace, "data"), 0o755)
        return nil
}

func Cleanup(workspace string, keepResults bool) error {
        if keepResults {
                // remove only temp dirs
                _ = os.RemoveAll(filepath.Join(workspace, "tmp"))
                return nil
        }
        return os.RemoveAll(workspace)
}
