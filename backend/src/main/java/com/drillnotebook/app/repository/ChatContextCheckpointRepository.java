package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.model.ContextSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** Single continuation publisher. Network generation happens before this short CAS transaction. */
@Repository
public class ChatContextCheckpointRepository {
    public record Checkpoint(long sessionId, long revision, long generation, long historyRevision, long policyRevision,
                             String memoryBoundary, Long memoryRevision,
                             String providerFingerprint, String protocol, String model, String strategyVersion,
                             String cipher, String crypto, long bytes, String createdAt) { }
    private final DataSource dataSource;
    private final ImmediateTransaction immediate;
    private final ChatStorageLedger ledger;

    public ChatContextCheckpointRepository(DataSource dataSource, ImmediateTransaction immediate, ChatStorageLedger ledger) {
        this.dataSource = dataSource; this.immediate = immediate; this.ledger = ledger;
    }

    public Optional<Checkpoint> read(long sessionId) throws SQLException {
        try (var connection = dataSource.getConnection()) { return readIn(connection, sessionId); }
    }

    public Long revision(long sessionId) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT revision FROM ai_context_checkpoint WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (var row = statement.executeQuery()) { return row.next() ? row.getLong(1) : null; }
        }
    }

    private static Optional<Checkpoint> readIn(Connection connection, long sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM ai_context_checkpoint WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (var r = statement.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Checkpoint(sessionId, r.getLong("revision"), r.getLong("context_generation"),
                        r.getLong("history_revision"), r.getLong("policy_revision"), r.getString("memory_boundary"),
                        r.getObject("memory_revision") == null ? null : r.getLong("memory_revision"), r.getString("provider_fingerprint"),
                        r.getString("protocol"), r.getString("model"), r.getString("strategy_version"),
                        r.getString("payload_cipher"), r.getString("payload_crypto"), r.getLong("byte_length"), r.getString("created_at")));
            }
        }
    }

    public String memoryBoundary() throws SQLException {
        try (var connection = dataSource.getConnection()) { return memoryBoundaryIn(connection); }
    }

    public boolean boundaryMatches(Checkpoint row) throws SQLException { return memoryBoundary().equals(row.memoryBoundary()); }

    private static String memoryBoundaryIn(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id, epoch FROM ai_memory_vault WHERE singleton = 1"); var row = statement.executeQuery()) {
            return row.next() ? row.getString(1) + ":" + row.getLong(2) : "absent";
        }
    }

    private static Long memoryRevisionIn(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision FROM ai_memory_vault WHERE singleton = 1"); var row = statement.executeQuery()) {
            return row.next() ? row.getLong(1) : null;
        }
    }

    public ContextSource anchor(long sessionId, long turnId) throws SQLException {
        try (var connection = dataSource.getConnection()) { return anchorIn(connection, sessionId, turnId).orElseThrow(); }
    }

    private static Optional<ContextSource> anchorIn(Connection connection, long sessionId, long turnId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT t.request_id, t.source_revision, t.request_meta_cipher, t.request_meta_crypto,
                  u.content, u.content_cipher, u.content_meta FROM ai_chat_turn t
                  JOIN ai_chat_message u ON u.id = t.user_message_id
                WHERE t.id = ? AND t.session_id = ? AND t.content_deleted_at IS NULL
                  AND t.status IN ('RUNNING', 'COMPLETED')
                """)) {
            statement.setLong(1, turnId); statement.setLong(2, sessionId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var identity = new StringBuilder();
                for (int i = 1; i <= 7; i++) if (i != 2) appendIdentity(identity, row.getString(i));
                // Accepted supplements are task anchors, never summary coverage that removes current input.
                try (var pending = connection.prepareStatement("SELECT request_id, payload_cipher, payload_meta, reply_offset FROM ai_chat_pending WHERE parent_request_id = ? AND state IN ('CLAIMED','SENDING','RECEIVED','UNKNOWN') ORDER BY request_id")) {
                    pending.setString(1, row.getString(1));
                    try (var items = pending.executeQuery()) { while (items.next()) for (int i = 1; i <= 4; i++) appendIdentity(identity, items.getString(i)); }
                }
                return Optional.of(new ContextSource("ANCHOR", String.valueOf(turnId), turnId, row.getString(1), 1,
                        com.drillnotebook.app.service.ChatContextEntryService.fingerprint(identity.toString())));
            }
        }
    }

    private static void appendIdentity(StringBuilder into, String value) { into.append(value == null ? -1 : value.length()).append(':'); if (value != null) into.append(value); }

    public boolean acquire(long sessionId, String lease, String fingerprint, long now, long expiresAt) throws SQLException {
        return immediate.execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ai_context_compaction_lease(session_id, lease_token, input_fingerprint, expires_at, state)
                    VALUES (?, ?, ?, ?, 'RUNNING')
                    ON CONFLICT(session_id) DO UPDATE SET lease_token = excluded.lease_token,
                      input_fingerprint = excluded.input_fingerprint, expires_at = excluded.expires_at, state = 'RUNNING'
                    WHERE (ai_context_compaction_lease.state != 'RUNNING' OR ai_context_compaction_lease.expires_at <= ?)
                      AND ai_context_compaction_lease.input_fingerprint != excluded.input_fingerprint
                    """)) {
                statement.setLong(1, sessionId); statement.setString(2, lease); statement.setString(3, fingerprint);
                statement.setLong(4, expiresAt); statement.setLong(5, now); return statement.executeUpdate() == 1;
            }
        });
    }

    public void fail(long sessionId, String lease) throws SQLException {
        immediate.execute(connection -> {
            try (var statement = connection.prepareStatement("UPDATE ai_context_compaction_lease SET state = 'FAILED' WHERE session_id = ? AND lease_token = ? AND state = 'RUNNING'")) {
                statement.setLong(1, sessionId); statement.setString(2, lease); statement.executeUpdate();
            }
            return null;
        });
    }

    public boolean sourcesMatch(long sessionId, long generation, long policyRevision, List<ContextSource> sources, long beforeTurnId) throws SQLException {
        try (var connection = dataSource.getConnection()) { return sourcesMatchIn(connection, sessionId, generation, policyRevision, sources, beforeTurnId); }
    }

    private static boolean sourcesMatchIn(Connection connection, long sessionId, long generation, long policyRevision,
                                          List<ContextSource> sources, long beforeTurnId) throws SQLException {
        var session = ChatSessionMetadataRepository.read(connection, sessionId);
        if (session.isEmpty() || session.get().contextGeneration() != generation) return false;
        try (var statement = connection.prepareStatement("SELECT revision FROM ai_memory_settings WHERE id = 1")) {
            try (var row = statement.executeQuery()) { if (row.next() && row.getLong(1) != policyRevision) return false; }
        }
        if (sources.size() > 65536 || sources.stream().anyMatch(source -> source.turnId() > beforeTurnId)) return false;
        var turns = sources.stream().filter(source -> "TURN".equals(source.kind())).toList();
        for (int from = 0; from < turns.size(); from += 256) {
            var batch = turns.subList(from, Math.min(turns.size(), from + 256));
            var expected = new java.util.HashMap<Long, ContextSource>();
            for (var source : batch) {
                if (source.turnId() >= beforeTurnId || !source.id().equals(String.valueOf(source.turnId()))
                        || expected.put(source.turnId(), source) != null) return false;
            }
            try (var statement = connection.prepareStatement("SELECT id, request_id, context_generation, source_revision, status, content_deleted_at"
                    + " FROM ai_chat_turn WHERE session_id = ? AND id IN (" + placeholders(batch.size()) + ")")) {
                statement.setLong(1, sessionId);
                for (int i = 0; i < batch.size(); i++) statement.setLong(i + 2, batch.get(i).turnId());
                try (var rows = statement.executeQuery()) { while (rows.next()) {
                    var source = expected.remove(rows.getLong(1));
                    if (source == null || !source.requestId().equals(rows.getString(2)) || generation != rows.getLong(3)
                            || source.revision() != rows.getLong(4) || !"COMPLETED".equals(rows.getString(5)) || rows.getString(6) != null
                            || !source.fingerprint().equals(com.drillnotebook.app.service.ChatContextEntryService.fingerprint(rows.getString(2) + ":" + rows.getLong(4)))) return false;
                } }
                if (!expected.isEmpty()) return false;
            }
        }
        var steps = sources.stream().filter(source -> "STEP".equals(source.kind())).toList();
        for (int from = 0; from < steps.size(); from += 256) {
            var batch = steps.subList(from, Math.min(steps.size(), from + 256));
            var expected = new java.util.HashMap<String, ContextSource>();
            for (var source : batch) if (expected.put(source.id(), source) != null) return false;
            try (var statement = connection.prepareStatement("SELECT e.id, e.turn_id, e.request_id, e.context_generation, e.revision, e.fingerprint, e.state,"
                    + " t.request_id, t.content_deleted_at, t.context_generation FROM ai_context_entry e JOIN ai_chat_turn t ON t.id=e.turn_id AND t.session_id=e.session_id"
                    + " WHERE e.session_id = ? AND e.id IN (" + placeholders(batch.size()) + ")")) {
                statement.setLong(1, sessionId);
                for (int i = 0; i < batch.size(); i++) statement.setString(i + 2, batch.get(i).id());
                try (var rows = statement.executeQuery()) { while (rows.next()) {
                    var source = expected.remove(rows.getString(1));
                    if (source == null || source.turnId() != rows.getLong(2) || !source.requestId().equals(rows.getString(3))
                            || generation != rows.getLong(4) || source.revision() != rows.getLong(5) || !source.fingerprint().equals(rows.getString(6))
                            || !"CLOSED".equals(rows.getString(7)) || !source.requestId().equals(rows.getString(8))
                            || rows.getString(9) != null || generation != rows.getLong(10)) return false;
                } }
                if (!expected.isEmpty()) return false;
            }
        }
        for (ContextSource source : sources) {
            if ("ANCHOR".equals(source.kind())) {
                var current = anchorIn(connection, sessionId, source.turnId());
                if (current.isEmpty() || !current.get().equals(source)) return false;
            } else if ("LEGACY".equals(source.kind())) {
                if (generation != 1 || !ChatHistoryRepository.legacySourceMatchesIn(connection, sessionId, source)) return false;
            } else if (!"TURN".equals(source.kind()) && !"STEP".equals(source.kind())) return false;
        }
        return true;
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }

    public boolean publish(Checkpoint next, Long expectedRevision, String lease, List<ContextSource> sources, long beforeTurnId, long now) throws SQLException {
        return publish(next, expectedRevision, lease, sources, beforeTurnId, now, () -> false);
    }

    public boolean publish(Checkpoint next, Long expectedRevision, String lease, List<ContextSource> sources, long beforeTurnId, long now,
                           java.util.function.BooleanSupplier cancelled) throws SQLException {
        ledger.ensureRebuiltInProcess();
        return immediate.execute(connection -> {
            if (cancelled.getAsBoolean()) return false;
            var session = ChatSessionMetadataRepository.read(connection, next.sessionId()).orElseThrow();
            if (session.historyRevision() != next.historyRevision()
                    || !memoryBoundaryIn(connection).equals(next.memoryBoundary())
                    || !java.util.Objects.equals(memoryRevisionIn(connection), next.memoryRevision())
                    || !sourcesMatchIn(connection, next.sessionId(), next.generation(), next.policyRevision(), sources, beforeTurnId)) return false;
            var old = readIn(connection, next.sessionId());
            if (expectedRevision == null ? old.isPresent() : old.isEmpty() || old.get().revision() != expectedRevision) return false;
            try (var statement = connection.prepareStatement("UPDATE ai_context_compaction_lease SET state = 'SUCCEEDED' WHERE session_id = ? AND lease_token = ? AND state = 'RUNNING' AND expires_at > ?")) {
                statement.setLong(1, next.sessionId()); statement.setString(2, lease); statement.setLong(3, now);
                if (statement.executeUpdate() != 1) return false;
            }
            long delta = next.bytes() - old.map(Checkpoint::bytes).orElse(0L);
            if (delta > 0 && Math.addExact(ledger.read(connection).usedHistory(), delta) > ledger.read(connection).historyLimitBytes())
                throw new com.drillnotebook.app.service.ChatTurnRejectException(409, "HISTORY_STORAGE_FULL", "会话容量不足，未发布上下文摘要", java.util.Map.of());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ai_context_checkpoint(session_id, revision, context_generation, history_revision, policy_revision, memory_boundary, memory_revision,
                      provider_fingerprint, protocol, model, strategy_version, payload_cipher, payload_crypto, byte_length, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET revision = excluded.revision, context_generation = excluded.context_generation,
                      history_revision = excluded.history_revision, policy_revision = excluded.policy_revision,
                      memory_boundary = excluded.memory_boundary, memory_revision = excluded.memory_revision,
                      provider_fingerprint = excluded.provider_fingerprint, protocol = excluded.protocol, model = excluded.model,
                      strategy_version = excluded.strategy_version, payload_cipher = excluded.payload_cipher,
                      payload_crypto = excluded.payload_crypto, byte_length = excluded.byte_length, created_at = excluded.created_at
                    """)) {
                statement.setLong(1, next.sessionId()); statement.setLong(2, next.revision()); statement.setLong(3, next.generation());
                statement.setLong(4, next.historyRevision()); statement.setLong(5, next.policyRevision());
                statement.setString(6, next.memoryBoundary()); statement.setObject(7, next.memoryRevision()); statement.setString(8, next.providerFingerprint());
                statement.setString(9, next.protocol()); statement.setString(10, next.model()); statement.setString(11, next.strategyVersion());
                statement.setString(12, next.cipher()); statement.setString(13, next.crypto()); statement.setLong(14, next.bytes()); statement.setString(15, next.createdAt());
                statement.executeUpdate();
            }
            ledger.addStoredHistory(connection, delta);
            return true;
        });
    }

    /** Dependent summary deletion releases its own bytes, separately from turn cleanup. */
    public static void invalidateIn(Connection connection, long sessionId) throws SQLException {
        try (var tables = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'ai_context_checkpoint'");
             var row = tables.executeQuery()) { if (!row.next()) return; }
        try (var lease = connection.prepareStatement("DELETE FROM ai_context_compaction_lease WHERE session_id = ?")) { lease.setLong(1, sessionId); lease.executeUpdate(); }
        var old = readIn(connection, sessionId);
        if (old.isEmpty()) return;
        try (var statement = connection.prepareStatement("DELETE FROM ai_context_checkpoint WHERE session_id = ?")) {
            statement.setLong(1, sessionId); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("UPDATE ai_chat_storage SET history_bytes = history_bytes - ?, revision = revision + 1 WHERE id = 1 AND history_bytes >= ?")) {
            statement.setLong(1, old.get().bytes()); statement.setLong(2, old.get().bytes());
            if (statement.executeUpdate() != 1) throw new SQLException("上下文摘要容量无法释放");
        }
    }

    /** Memory forgetting or policy changes invalidate every dependent view in the same transaction. */
    public static void invalidateAllIn(Connection connection) throws SQLException {
        // Older schema fixtures and migrations can call memory repositories before the new tables exist.
        try (var tables = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'ai_context_checkpoint'");
             var row = tables.executeQuery()) { if (!row.next()) return; }
        long bytes;
        try (var statement = connection.prepareStatement("SELECT COALESCE(SUM(byte_length), 0) FROM ai_context_checkpoint");
             var row = statement.executeQuery()) { row.next(); bytes = row.getLong(1); }
        try (var statement = connection.prepareStatement("DELETE FROM ai_context_compaction_lease")) { statement.executeUpdate(); }
        try (var statement = connection.prepareStatement("DELETE FROM ai_context_checkpoint")) { statement.executeUpdate(); }
        if (bytes > 0) try (var statement = connection.prepareStatement(
                "UPDATE ai_chat_storage SET history_bytes = history_bytes - ?, revision = revision + 1 WHERE id = 1 AND history_bytes >= ?")) {
            statement.setLong(1, bytes); statement.setLong(2, bytes);
            if (statement.executeUpdate() != 1) throw new SQLException("上下文摘要容量无法释放");
        }
    }
}
