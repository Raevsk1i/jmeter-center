package logging

import (
        "io"
        "os"
)

func ReadFrom(path string, offset int64, max int) (data []byte, next int64, eof bool, err error) {
        f, err := os.Open(path)
        if err != nil {
                return nil, offset, true, err
        }
        defer f.Close()
        info, err := f.Stat()
        if err != nil {
                return nil, offset, true, err
        }
        if offset > info.Size() {
                offset = info.Size()
        }
        if _, err := f.Seek(offset, io.SeekStart); err != nil {
                return nil, offset, true, err
        }
        buf := make([]byte, max)
        n, err := f.Read(buf)
        if err != nil && err != io.EOF {
                return nil, offset, false, err
        }
        next = offset + int64(n)
        eof = next >= info.Size()
        return buf[:n], next, eof, nil
}
