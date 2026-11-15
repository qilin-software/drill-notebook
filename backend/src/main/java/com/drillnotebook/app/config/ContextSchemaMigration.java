package com.drillnotebook.app.config;

import java.sql.Connection;
import java.sql.SQLException;

/** SQLite CHECK widening preserves every usage row; the initializer makes a portable backup first. */
final class ContextSchemaMigration {
    private ContextSchemaMigration() { }
    static void widenUsagePurpose(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ai_usage_attempt_v22 (
                      id TEXT PRIMARY KEY, operation_id TEXT NOT NULL, context_plan_id TEXT,
                      purpose TEXT NOT NULL CHECK (purpose IN ('CHAT', 'CONTEXT_COMPACT', 'MEMORY_EXTRACT', 'SESSION_SUMMARY', 'FORMAT_REPAIR')),
                      target_fingerprint TEXT NOT NULL, budget_date TEXT NOT NULL, budget_timezone TEXT NOT NULL,
                      reserved_tokens INTEGER NOT NULL, input_tokens INTEGER, output_tokens INTEGER,
                      cache_read_tokens INTEGER, cache_write_tokens INTEGER,
                      cache_read_included_in_input INTEGER CHECK (cache_read_included_in_input IN (0, 1)), context_breakdown TEXT,
                      usage_source TEXT NOT NULL CHECK (usage_source IN ('provider', 'estimate', 'unknown')),
                      state TEXT NOT NULL CHECK (state IN ('RESERVED', 'SENT', 'SETTLED', 'UNKNOWN', 'RELEASED')),
                      created_at TEXT NOT NULL, settled_at TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO ai_usage_attempt_v22(id, operation_id, context_plan_id, purpose, target_fingerprint,
                      budget_date, budget_timezone, reserved_tokens, input_tokens, output_tokens, cache_read_tokens,
                      cache_write_tokens, cache_read_included_in_input, context_breakdown, usage_source, state, created_at, settled_at)
                    SELECT id, operation_id, context_plan_id, purpose, target_fingerprint, budget_date, budget_timezone,
                      reserved_tokens, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                      cache_read_included_in_input, context_breakdown, usage_source, state, created_at, settled_at FROM ai_usage_attempt
                    """);
            statement.execute("DROP TABLE ai_usage_attempt");
            statement.execute("ALTER TABLE ai_usage_attempt_v22 RENAME TO ai_usage_attempt");
            statement.execute("CREATE INDEX idx_ai_usage_operation ON ai_usage_attempt(operation_id, purpose)");
        }
    }
}
