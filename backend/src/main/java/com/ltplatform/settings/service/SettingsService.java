package com.ltplatform.settings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltplatform.security.crypto.SecretBox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretBox secretBox;

    public SettingsService(JdbcTemplate jdbc, ObjectMapper mapper, SecretBox secretBox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.secretBox = secretBox;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMap(String key) {
        var rows = jdbc.queryForList("SELECT value_json::text AS value_json FROM application_settings WHERE key = ?", key);
        if (rows.isEmpty()) return new HashMap<>();
        try {
            String json = String.valueOf(rows.getFirst().get("value_json"));
            Map<String, Object> map = mapper.readValue(json, new TypeReference<>() {});
            if (map.containsKey("tokenEnc")) {
                byte[] enc = Base64.getDecoder().decode(String.valueOf(map.get("tokenEnc")));
                map.put("token", secretBox.decryptString(enc));
                map.remove("tokenEnc");
            }
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @Transactional
    public void putMap(String key, Map<String, Object> value) {
        try {
            Map<String, Object> toStore = new HashMap<>(value);
            if (toStore.containsKey("token") && toStore.get("token") != null) {
                String token = String.valueOf(toStore.remove("token"));
                if (!token.isBlank() && !token.equals("********")) {
                    toStore.put("tokenEnc", Base64.getEncoder().encodeToString(secretBox.encryptString(token)));
                } else {
                    Map<String, Object> existing = getRaw(key);
                    if (existing.containsKey("tokenEnc")) {
                        toStore.put("tokenEnc", existing.get("tokenEnc"));
                    }
                }
            }
            String json = mapper.writeValueAsString(toStore);
            int updated = jdbc.update(
                    "UPDATE application_settings SET value_json = ?::jsonb, updated_at = ? WHERE key = ?",
                    json, Timestamp.from(Instant.now()), key
            );
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO application_settings(key, value_json, updated_at) VALUES (?, ?::jsonb, ?)",
                        key, json, Timestamp.from(Instant.now())
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save settings", e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPublic(String key) {
        Map<String, Object> map = new HashMap<>(getMap(key));
        if (map.containsKey("token")) {
            map.put("token", "********");
        }
        return map;
    }

    private Map<String, Object> getRaw(String key) {
        var rows = jdbc.queryForList("SELECT value_json::text AS value_json FROM application_settings WHERE key = ?", key);
        if (rows.isEmpty()) return Map.of();
        try {
            String json = String.valueOf(rows.getFirst().get("value_json"));
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
