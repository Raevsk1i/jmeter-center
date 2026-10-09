package com.ltplatform.logging.service;

import com.ltplatform.config.LtPlatformProperties;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class LogStorageService {
    private final Path logRoot;
    private final Map<String, List<Consumer<LogEvent>>> subscribers = new ConcurrentHashMap<>();

    public LogStorageService(LtPlatformProperties props) {
        this.logRoot = Path.of(props.getLogRoot());
    }

    public synchronized void append(String runId, String generatorId, String logFile, long offset, byte[] data) {
        try {
            Path dir = logRoot.resolve(runId).resolve(generatorId);
            Files.createDirectories(dir);
            Path file = dir.resolve(logFile == null || logFile.isBlank() ? "jmeter.log" : logFile);
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                if (offset >= 0) {
                    raf.seek(Math.min(offset, raf.length()));
                } else {
                    raf.seek(raf.length());
                }
                raf.write(data);
            }
            LogEvent event = new LogEvent(runId, generatorId, logFile, offset, new String(data));
            List<Consumer<LogEvent>> listeners = subscribers.get(runId);
            if (listeners != null) {
                listeners.forEach(l -> l.accept(event));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store log chunk", e);
        }
    }

    public String read(String runId, String generatorId, String logFile, long fromOffset, int maxBytes) {
        try {
            Path file = logRoot.resolve(runId).resolve(generatorId)
                    .resolve(logFile == null || logFile.isBlank() ? "jmeter.log" : logFile);
            if (!Files.exists(file)) {
                return "";
            }
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                long start = Math.max(0, fromOffset);
                if (start >= raf.length()) return "";
                raf.seek(start);
                int len = (int) Math.min(maxBytes, raf.length() - start);
                byte[] buf = new byte[len];
                raf.readFully(buf);
                return new String(buf);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read logs", e);
        }
    }

    public List<Path> listGenerators(String runId) throws IOException {
        Path dir = logRoot.resolve(runId);
        if (!Files.exists(dir)) return List.of();
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(result::add);
        }
        return result;
    }

    public AutoCloseable subscribe(String runId, Consumer<LogEvent> consumer) {
        subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(consumer);
        return () -> {
            List<Consumer<LogEvent>> list = subscribers.get(runId);
            if (list != null) list.remove(consumer);
        };
    }

    public record LogEvent(String runId, String generatorId, String logFile, long offset, String text) {}
}
