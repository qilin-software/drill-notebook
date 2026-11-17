package com.drillnotebook.app.service;

import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** 当前主模型实际请求及其响应占用；历史存储、累计用量和原生联网子请求不参与拼接。 */
@Service
public class SessionContextOccupancyService {
    private final DataSource source;
    private final ChatTurnService turns;
    private final ObjectMapper mapper;

    public SessionContextOccupancyService(DataSource source, ChatTurnService turns, ObjectMapper mapper) {
        this.source = source; this.turns = turns; this.mapper = mapper;
    }

    public Map<String, Object> read(long sessionId) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var state = ChatSessionMetadataRepository.read(connection, sessionId).orElseThrow();
            ModelContextSnapshot snapshot = null;
            String requestId = null;
            boolean active = false;
            try (var statement = connection.prepareStatement("""
                    SELECT t.id, t.request_id, t.status, t.result_meta_crypto,
                      EXISTS (SELECT 1 FROM ai_usage_attempt u WHERE u.operation_id = t.request_id
                        AND u.purpose = 'CHAT' AND u.state IN ('SENT', 'SETTLED', 'UNKNOWN')) AS has_main_request
                    FROM ai_chat_turn t
                    WHERE t.session_id = ? AND t.context_generation = ? AND t.content_deleted_at IS NULL
                    ORDER BY CASE WHEN t.status = 'RUNNING' THEN 0 ELSE 1 END,
                      julianday(COALESCE(t.finished_at, t.started_at)) DESC, t.request_id DESC, t.id DESC
                    """)) {
                statement.setLong(1, sessionId); statement.setLong(2, state.contextGeneration());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String candidate = rows.getString("request_id");
                        boolean running = "RUNNING".equals(rows.getString("status"));
                        String crypto = rows.getString("result_meta_crypto");
                        boolean blocksLegacy = blocksLegacyFallback(crypto);
                        var frame = running ? turns.currentModelContext(candidate) : stored(crypto);
                        // A modern snapshot that failed validation is unavailable, never a legacy record.
                        if (frame == null && !blocksLegacy) frame = legacy(connection, candidate, state.contextGeneration(), state.historyRevision());
                        if (frame == null) {
                            // Preparation and summary calls do not replace the main model's last actual window.
                            // An uninspectable actual request is a barrier: do not mislabel an older frame as current.
                            if (rows.getBoolean("has_main_request") || blocksLegacy) {
                                connection.commit(); return null;
                            }
                            continue;
                        }
                        if (frame.contextGeneration() != state.contextGeneration()
                                || !running && !validAfterUnsentAppends(connection, sessionId, rows.getLong("id"), frame, state)) {
                            connection.commit(); return null;
                        }
                        requestId = candidate; snapshot = frame; active = running; break;
                    }
                }
            }
            connection.commit();
            if (snapshot == null) return null;
            var breakdown = snapshot.breakdown();
            var result = new LinkedHashMap<String, Object>();
            result.put("tokens", breakdown.tokens());
            result.put("categories", breakdown.categories().entrySet().stream()
                    .map(entry -> Map.of("kind", entry.getKey().name(), "tokens", entry.getValue())).toList());
            result.put("estimated", true); result.put("complete", snapshot.complete());
            result.put("estimatorVersion", snapshot.estimatorVersion());
            result.put("model", snapshot.model()); result.put("contextWindow", snapshot.contextWindow());
            result.put("contextGeneration", snapshot.contextGeneration()); result.put("historyRevision", snapshot.historyRevision());
            result.put("requestId", requestId); result.put("active", active); result.put("summaryRevision", null);
            return result;
        } catch (SQLException error) { throw new IllegalStateException("当前模型上下文暂时无法统计", error); }
    }

    /** Only untouched begin/end appends may account for a revision change; edits, cleanup and retries remain invalidations. */
    private boolean validAfterUnsentAppends(Connection connection, long sessionId, long turnId, ModelContextSnapshot frame,
                                           ChatSessionMetadataRepository.State state) throws SQLException {
        if (frame.historyRevision() == state.historyRevision()) return true;
        if (frame.historyRevision() > state.historyRevision()) return false;
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(CASE WHEN status = 'RUNNING' AND source_revision = 1 THEN 1
                       WHEN status <> 'RUNNING' AND source_revision = 2 THEN 2 ELSE 0 END), 0),
                  COALESCE(SUM(CASE WHEN content_deleted_at IS NOT NULL
                       OR source_revision <> CASE WHEN status = 'RUNNING' THEN 1 ELSE 2 END THEN 1 ELSE 0 END), 0)
                FROM ai_chat_turn WHERE session_id = ? AND context_generation = ? AND id > ?
                """)) {
            statement.setLong(1, sessionId); statement.setLong(2, state.contextGeneration()); statement.setLong(3, turnId);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(2) == 0
                        && state.historyRevision() - frame.historyRevision() == rows.getLong(1);
            }
        }
    }

    private boolean blocksLegacyFallback(String crypto) {
        if (crypto == null || crypto.isBlank()) return false;
        if (crypto.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RequestContextAudit.MAX_BYTES) return true;
        try {
            var metadata = mapper.readTree(crypto);
            return !metadata.isObject() || metadata.has("modelContext");
        } catch (Exception ignored) { return true; }
    }

    private ModelContextSnapshot stored(String crypto) {
        if (crypto == null || crypto.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RequestContextAudit.MAX_BYTES) return null;
        try {
            var snapshot = mapper.readTree(crypto).path("modelContext");
            return snapshot.isObject() ? mapper.treeToValue(snapshot, ModelContextSnapshot.class) : null;
        } catch (Exception ignored) { return null; }
    }

    /** 旧版本只使用同一轮最后一份主请求快照；缺少该次输出时显示下界，不加整轮拼接后的回答。 */
    private ModelContextSnapshot legacy(Connection connection, String requestId, long generation, long revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT context_breakdown, output_tokens FROM ai_usage_attempt WHERE operation_id = ? AND purpose = 'CHAT'
                  AND state IN ('SENT', 'SETTLED', 'UNKNOWN') ORDER BY created_at DESC, id DESC
                """)) {
            statement.setString(1, requestId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String json = rows.getString(1);
                    if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RequestContextAudit.MAX_BYTES) return null;
                    try {
                        var audit = mapper.readValue(json, RequestContextAudit.class);
                        if (!"CHAT".equals(audit.kind())) continue;
                        var result = ModelContextSnapshot.from(audit, generation, revision);
                        long output = rows.getLong(2);
                        return rows.wasNull() ? result.incomplete() : result.withOutput(output);
                    } catch (Exception ignored) { return null; }
                }
            }
        }
        return null;
    }
}
