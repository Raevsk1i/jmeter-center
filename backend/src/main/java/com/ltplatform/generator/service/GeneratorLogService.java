package com.ltplatform.generator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GeneratorLogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<UUID, List<Consumer<LogEntry>>> subscribers = new ConcurrentHashMap<>();

    public GeneratorLogService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record LogEntry(
            long id,
            UUID generatorId,
            String source,
            String level,
            String eventType,
            String message,
            Map<String, Object> detail,
            String at
    ) {}

    public void info(UUID generatorId, String source, String eventType, String message) {
        append(generatorId, source, "INFO", eventType, message, null);
    }

    public void warn(UUID generatorId, String source, String eventType, String message) {
        append(generatorId, source, "WARN", eventType, message, null);
    }

    public void error(UUID generatorId, String source, String eventType, String message) {
        append(generatorId, source, "ERROR", eventType, message, null);
    }

    public void append(UUID generatorId, String source, String level, String eventType,
                       String message, Map<String, Object> detail) {
        if (generatorId == null) return;
        try {
            String json = detail == null ? null : mapper.writeValueAsString(detail);
            jdbc.update(
                    """
                    INSERT INTO generator_logs(generator_id, source, level, event_type, message, detail, at)
                    VALUES (?,?,?,?,?,?::jsonb,?)
                    """,
                    generatorId, source, level, eventType,
                    truncate(message, 8000),
                    json,
                    Timestamp.from(Instant.now())
            );
            Long id = jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('generator_logs','id'))", Long.class);
            LogEntry entry = new LogEntry(
                    id != null ? id : 0L,
                    generatorId,
                    source,
                    level,
                    eventType,
                    truncate(message, 8000),
                    detail,
                    Instant.now().toString()
            );
            List<Consumer<LogEntry>> listeners = subscribers.get(generatorId);
            if (listeners != null) {
                listeners.forEach(l -> {
                    try { l.accept(entry); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {
            // diagnostics must never break main flow
        }
    }

    public List<LogEntry> list(UUID generatorId, String source, Long afterId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 1000);
        StringBuilder sql = new StringBuilder("""
                SELECT id, generator_id, source, level, event_type, message, detail::text AS detail, at
                FROM generator_logs
                WHERE generator_id = ?
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(generatorId);
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            args.add(source);
        }
        if (afterId != null && afterId > 0) {
            sql.append(" AND id > ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(lim);

        List<LogEntry> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> detail = null;
            String raw = rs.getString("detail");
            if (raw != null) {
                try {
                    detail = mapper.readValue(raw, mapper.getTypeFactory()
                            .constructMapType(Map.class, String.class, Object.class));
                } catch (Exception e) {
                    detail = Map.of("raw", raw);
                }
            }
            return new LogEntry(
                    rs.getLong("id"),
                    (UUID) rs.getObject("generator_id"),
                    rs.getString("source"),
                    rs.getString("level"),
                    rs.getString("event_type"),
                    rs.getString("message"),
                    detail,
                    rs.getTimestamp("at").toInstant().toString()
            );
        }, args.toArray());
        // return chronological for UI
        java.util.Collections.reverse(rows);
        return rows;
    }

    public AutoCloseable subscribe(UUID generatorId, Consumer<LogEntry> consumer) {
        subscribers.computeIfAbsent(generatorId, k -> new CopyOnWriteArrayList<>()).add(consumer);
        return () -> {
            List<Consumer<LogEntry>> list = subscribers.get(generatorId);
            if (list != null) list.remove(consumer);
        };
    }

    public Map<String, Object> sources(UUID generatorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source, COUNT(*) AS cnt FROM generator_logs WHERE generator_id = ? GROUP BY source ORDER BY source",
                generatorId
        );
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(String.valueOf(row.get("source")), row.get("cnt"));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
