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
            String requestId = null, status = null, crypto = null;
            try (var statement = connection.prepareStatement("""
                    SELECT request_id, status, result_meta_crypto FROM ai_chat_turn
                    WHERE session_id = ? AND context_generation = ? AND content_deleted_at IS NULL
                    ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END,
                      julianday(COALESCE(finished_at, started_at)) DESC, request_id DESC, id DESC LIMIT 1
                    """)) {
                statement.setLong(1, sessionId); statement.setLong(2, state.contextGeneration());
                try (var row = statement.executeQuery()) {
                    if (row.next()) { requestId = row.getString(1); status = row.getString(2); crypto = row.getString(3); }
                }
            }
            if (requestId == null) { connection.commit(); return null; }
            boolean active = "RUNNING".equals(status);
            ModelContextSnapshot snapshot = active ? turns.currentModelContext(requestId) : stored(crypto);
            if (snapshot != null && (snapshot.contextGeneration() != state.contextGeneration()
                    || !active && snapshot.historyRevision() != state.historyRevision())) {
                // 清理或编辑后，旧实际请求已不代表新的会话状态；等下次真实请求，不编造预览输入。
                connection.commit(); return null;
            }
            if (snapshot == null) snapshot = legacy(connection, requestId, state.contextGeneration(), state.historyRevision());
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
