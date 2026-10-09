package com.ltplatform.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String actor, String action, String entityType, String entityId, Map<String, Object> detail) {
        try {
            String json = detail == null ? null : mapper.writeValueAsString(detail);
            jdbc.update(
                    "INSERT INTO audit_log(actor, action, entity_type, entity_id, detail, at) VALUES (?,?,?,?,?::jsonb,?)",
                    actor, action, entityType, entityId, json, Timestamp.from(Instant.now())
            );
        } catch (Exception e) {
            // audit must not break main flow
        }
    }
}
