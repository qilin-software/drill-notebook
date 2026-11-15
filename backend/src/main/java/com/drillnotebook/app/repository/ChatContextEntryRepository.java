package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.service.ChatTurnRejectException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** Encrypted closed tool steps; capacity and turn identity change in one SQLite transaction. */
@Repository
public class ChatContextEntryRepository {
    public record Entry(String id, long sessionId, long turnId, String requestId, long generation,
                        int sequence, long revision, String kind, String state, String providerFingerprint,
                        String protocol, String model, String cipher, String crypto, String fingerprint,
                        int estimatedTokens, long bytes, String createdAt) { }
    private static final String SELECT = "SELECT * FROM ai_context_entry";
    private final DataSource dataSource;
    private final ImmediateTransaction immediate;
    private final ChatStorageLedger ledger;

    public ChatContextEntryRepository(DataSource dataSource, ImmediateTransaction immediate, ChatStorageLedger ledger) {
        this.dataSource = dataSource; this.immediate = immediate; this.ledger = ledger;
    }

    public Entry save(Entry entry, Long expectedRevision) throws SQLException {
        ledger.ensureRebuiltInProcess();
        return immediate.execute(connection -> {
            try (var turn = connection.prepareStatement("SELECT request_id, context_generation, status, content_deleted_at FROM ai_chat_turn WHERE id = ? AND session_id = ?")) {
                turn.setLong(1, entry.turnId()); turn.setLong(2, entry.sessionId());
                try (var row = turn.executeQuery()) {
                    if (!row.next() || !entry.requestId().equals(row.getString(1)) || entry.generation() != row.getLong(2)
                            || !"RUNNING".equals(row.getString(3)) || row.getString(4) != null)
                        throw new ChatTurnRejectException(409, "CONTEXT_CHANGED", "轮次已变化，不能保存工具步骤", java.util.Map.of());
                }
            }
            var existing = find(connection, entry.id());
            if (expectedRevision == null ? existing.isPresent() : existing.isEmpty() || existing.get().revision() != expectedRevision)
                throw new SQLException("上下文步骤版本冲突");
            if (existing.isPresent() && (!"OPEN".equals(existing.get().state()) || existing.get().turnId() != entry.turnId()))
                throw new SQLException("已闭合步骤不可改写");
            long delta = entry.bytes() - existing.map(Entry::bytes).orElse(0L);
            var storage = ledger.read(connection);
            if (delta > 0 && Math.addExact(storage.usedHistory(), delta) > storage.historyLimitBytes())
                throw new ChatTurnRejectException(409, "HISTORY_STORAGE_FULL", "会话容量不足，工具结果已停止外发，请清理空间", java.util.Map.of());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ai_context_entry(id, session_id, turn_id, request_id, context_generation, sequence,
                      revision, kind, state, provider_fingerprint, protocol, model, payload_cipher, payload_crypto,
                      fingerprint, estimated_tokens, byte_length, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET revision = excluded.revision, state = excluded.state,
                      payload_cipher = excluded.payload_cipher, payload_crypto = excluded.payload_crypto,
                      fingerprint = excluded.fingerprint, estimated_tokens = excluded.estimated_tokens, byte_length = excluded.byte_length
                    """)) {
                statement.setString(1, entry.id()); statement.setLong(2, entry.sessionId()); statement.setLong(3, entry.turnId());
                statement.setString(4, entry.requestId()); statement.setLong(5, entry.generation()); statement.setInt(6, entry.sequence());
                statement.setLong(7, entry.revision()); statement.setString(8, entry.kind()); statement.setString(9, entry.state());
                statement.setString(10, entry.providerFingerprint()); statement.setString(11, entry.protocol()); statement.setString(12, entry.model());
                statement.setString(13, entry.cipher()); statement.setString(14, entry.crypto()); statement.setString(15, entry.fingerprint());
                statement.setInt(16, entry.estimatedTokens()); statement.setLong(17, entry.bytes()); statement.setString(18, entry.createdAt());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE ai_chat_turn SET stored_history_bytes = stored_history_bytes + ? WHERE id = ?")) {
                statement.setLong(1, delta); statement.setLong(2, entry.turnId()); statement.executeUpdate();
            }
            ledger.addStoredHistory(connection, delta);
            return entry;
        });
    }

    public int nextSequence(long turnId) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT COALESCE(MAX(sequence), 0) + 1 FROM ai_context_entry WHERE turn_id = ?")) {
            statement.setLong(1, turnId); try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    public List<Entry> readTurn(long turnId) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(SELECT + " WHERE turn_id = ? ORDER BY sequence")) {
            statement.setLong(1, turnId); var result = new ArrayList<Entry>();
            long bytes = 0;
            try (var rows = statement.executeQuery()) { while (rows.next()) {
                Entry entry = row(rows); bytes = Math.addExact(bytes, entry.bytes());
                if (bytes > 32L * 1024 * 1024 || result.size() >= 4096) throw new ChatTurnRejectException(413,
                        "CONTEXT_HISTORY_SCAN_LIMIT", "本轮工具记录超过安全读取范围，未省略历史外发", java.util.Map.of());
                result.add(entry);
            } }
            return List.copyOf(result);
        }
    }

    public Optional<Entry> find(String id) throws SQLException {
        try (var connection = dataSource.getConnection()) { return find(connection, id); }
    }

    public String memoryBoundary() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT id, epoch FROM ai_memory_vault WHERE singleton = 1"); var row = statement.executeQuery()) {
            return row.next() ? row.getString(1) + ":" + row.getLong(2) : "absent";
        }
    }

    static Optional<Entry> find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT + " WHERE id = ?")) {
            statement.setString(1, id); try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(row(rows)) : Optional.empty(); }
        }
    }

    private static Entry row(java.sql.ResultSet r) throws SQLException {
        return new Entry(r.getString("id"), r.getLong("session_id"), r.getLong("turn_id"), r.getString("request_id"),
                r.getLong("context_generation"), r.getInt("sequence"), r.getLong("revision"), r.getString("kind"), r.getString("state"),
                r.getString("provider_fingerprint"), r.getString("protocol"), r.getString("model"), r.getString("payload_cipher"),
                r.getString("payload_crypto"), r.getString("fingerprint"), r.getInt("estimated_tokens"), r.getLong("byte_length"), r.getString("created_at"));
    }

    public static void clearTurnIn(Connection connection, long turnId) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM ai_context_entry WHERE turn_id = ?")) {
            statement.setLong(1, turnId); statement.executeUpdate();
        }
    }
}
