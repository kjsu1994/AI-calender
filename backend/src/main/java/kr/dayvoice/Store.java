package kr.dayvoice;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;

@Component
class Store {
    final JdbcTemplate db;
    Store(JdbcTemplate db) { this.db = db; }
    record User(String id, String email, String name, String refreshToken, String calendarId,
        String calendarName, String syncToken, String syncStatus, String lastSync, boolean demo) {}
    record Event(String id, String userId, String googleId, String etag, JsonNode data,
        JsonNode remote, String syncState, long version, boolean deleted, boolean recurring) {
        Map<String, Object> view() {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", id); v.put("data", Json.plain(data)); v.put("remote", remote == null ? null : Json.plain(remote));
            v.put("syncState", syncState); v.put("version", version); v.put("deleted", deleted); v.put("recurring", recurring);
            return v;
        }
    }
    User user(String id) {
        return db.query("SELECT * FROM app_user WHERE id=?", (r, n) -> new User(r.getString("id"), r.getString("email"),
            r.getString("display_name"), r.getString("refresh_token"), r.getString("calendar_id"), r.getString("calendar_name"),
            r.getString("sync_token"), r.getString("sync_status"), r.getString("last_sync"), r.getBoolean("demo")), id)
            .stream().findFirst().orElseThrow(() -> new ApiException(401, "로그인이 필요합니다."));
    }
    void lock(String user) { db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user); }
    List<Event> events(String user) {
        return db.query("SELECT * FROM calendar_event WHERE user_id=?", (r,n) -> new Event(r.getString("id"),r.getString("user_id"),
            r.getString("google_id"),r.getString("etag"),Json.read(r.getString("payload")),
            r.getString("remote_payload") == null ? null : Json.read(r.getString("remote_payload")),r.getString("sync_state"),
            r.getLong("version"),r.getBoolean("deleted"),r.getBoolean("recurring")), user);
    }
    Event event(String user, String id) {
        return events(user).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(404, "일정을 찾을 수 없습니다."));
    }
}
