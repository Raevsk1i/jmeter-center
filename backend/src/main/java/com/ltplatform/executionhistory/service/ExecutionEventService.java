package com.ltplatform.executionhistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExecutionEventService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExecutionEventService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void emit(UUID runId, UUID generatorId, String entityType, String entityId,
                     String eventType, String message, Map<String, Object> detail) {
        try {
            String json = detail == null ? null : mapper.writeValueAsString(detail);
            jdbc.update(
                    """
                    INSERT INTO execution_events(run_id, generator_id, entity_type, entity_id, event_type, message, detail, at)
                    VALUES (?,?,?,?,?,?,?::jsonb,?)
                    """,
                    runId, generatorId, entityType, entityId, eventType, message, json, Timestamp.from(Instant.now())
            );
        } catch (Exception ignored) {
        }
    }

    public List<Map<String, Object>> listForRun(UUID runId) {
        return jdbc.query(
                """
                SELECT id, run_id, generator_id, entity_type, entity_id, event_type, message, detail::text AS detail, at
                FROM execution_events WHERE run_id = ? ORDER BY at
                """,
                (rs, i) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("runId", rs.getObject("run_id"));
                    row.put("generatorId", rs.getObject("generator_id"));
                    row.put("entityType", rs.getString("entity_type"));
                    row.put("entityId", rs.getString("entity_id"));
                    row.put("eventType", rs.getString("event_type"));
                    row.put("message", rs.getString("message"));
                    String detail = rs.getString("detail");
                    if (detail != null) {
                        try {
                            row.put("detail", mapper.readValue(detail, Map.class));
                        } catch (Exception e) {
                            row.put("detail", detail);
                        }
                    } else {
                        row.put("detail", null);
                    }
                    row.put("at", rs.getTimestamp("at").toInstant().toString());
                    return row;
                },
                runId
        );
    }
}
