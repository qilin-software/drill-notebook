package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.model.RequestContextAudit.Kind;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.UsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 模型窗口按实际请求快照统计；隔离数据库中验证，不读取用户会话。 */
class SessionContextOccupancyTest {
    private Path root;
    private DataSource source;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private SessionContextOccupancyService occupancy;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sessionId;
    private int sequence;
    private record Turn(long id, String requestId) { }

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("context-occupancy-");
        source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        occupancy = new SessionContextOccupancyService(source, wiring.turnService(), mapper);
        sessionId = wiring.sessions().create("实际模型上下文", null);
    }

    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }

    private Turn turn(long session, long generation, String text) {
        String request = UuidV7.generate(System.currentTimeMillis() + ++sequence);
        String now = Instant.parse("2026-11-15T12:00:00Z").plusSeconds(sequence).toString();
        jdbc.update("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,
                    context_generation, source_scope_type, source_scope_id, source_timezone, runtime_generation,
                    started_at, finished_at, persistence_status)
                VALUES (?, ?, 'COMPLETED', 'fixture', '{}', ?, 'GLOBAL', 0, 'Asia/Singapore', 'test', ?, ?, 'saved')
                """, request, session, generation, now, now);
        long id = jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE request_id = ?", Long.class, request);
        for (String role : List.of("user", "assistant"))
            jdbc.update("INSERT INTO ai_chat_message(session_id, turn_id, role, content) VALUES (?, ?, ?, ?)", session, id, role, text);
        return new Turn(id, request);
    }

    private Turn turn() { return turn(sessionId, 1, "保存在本地但未必外发的长历史".repeat(100)); }

    private RequestContextAudit audit(String input) {
        return audit(Map.of("model", "fixture", "messages", List.of(
                Map.of("role", "system", "content", "固定规则"),
                Map.of("role", "user", "content", input))), "CHAT");
    }

    private RequestContextAudit audit(Map<String, Object> wire, String kind) {
        return new RequestContextAnalyzer(mapper).analyze(wire, 128000, kind, Set.of("read_note", "web_search"));
    }

    private ModelContextSnapshot snapshot(RequestContextAudit input) throws Exception {
        var state = wiring.metadata().read(sessionId).orElseThrow();
        return ModelContextSnapshot.from(input, state.contextGeneration(), state.historyRevision());
    }

    private void save(Turn turn, ModelContextSnapshot snapshot) throws Exception {
        var encrypted = wiring.cipher().withModelContext(wiring.cipher().encrypt("fixture-result", "test-only"), snapshot);
        jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?", encrypted.meta(), turn.id());
    }

    private static long tokens(Map<?, ?> value) { return ((Number) value.get("tokens")).longValue(); }
    private Map<String, Object> read() { return occupancy.read(sessionId); }

    private static Map<String, Long> categories(Map<String, Object> value) {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (Object raw : (List<?>) value.get("categories")) {
            var category = (Map<?, ?>) raw;
            result.put((String) category.get("kind"), ((Number) category.get("tokens")).longValue());
        }
        assertEquals(tokens(value), result.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    @Test void localHistoryAndOldExtraMetadataDoNotInventAnActualContext() throws Exception {
        assertNull(read());
        Turn current = turn();
        jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?",
                mapper.writeValueAsString(Map.of("contextExtraTokens", 900000, "tokenEstimatorVersion", TokenEstimator.VERSION)), current.id());
        wiring.sessions().recordTurnContext(current.requestId(), 800000, 1000000, 700000, 9000);
        assertNull(read(), "历史正文、旧额外数值和供应商累计输入都不能替代实际请求快照");
    }

    @Test void sixHundredStoredMessagesDoNotAddContentTheModelNeverReceived() throws Exception {
        for (int round = 0; round < 300; round++) turn();
        Turn latest = turn();
        var input = audit("最后一份实际请求");
        save(latest, snapshot(input).withOutput(70));
        var ledger = wiring.ledger().read();
        var current = read();
        assertEquals((long) input.estimatedInputTokens() + 70, tokens(current));
        assertEquals(602, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(ledger, wiring.ledger().read(), "读取统计不改历史、容量账本或外发请求");
        assertEquals(current, read());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt", Integer.class));
    }

    @Test void actualToolDefinitionsCallsResultsAndImagesComeFromTheSameWireRequest() throws Exception {
        Turn current = turn();
        var wire = Map.<String, Object>of("model", "fixture", "tools", List.of(
                Map.of("type", "function", "function", Map.of("name", "read_note", "parameters", Map.of("type", "object")))),
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "查询内容"),
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,fixture")))),
                        Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call-1", "function",
                                Map.of("name", "read_note", "arguments", "{\"query\":\"fixture\"}")))),
                        Map.of("role", "tool", "tool_call_id", "call-1", "content", "工具返回的证据")));
        var input = audit(wire, "CHAT");
        save(current, snapshot(input).withOutput(25));
        var parts = categories(read());
        for (Kind kind : List.of(Kind.TOOL_DEFINITIONS, Kind.TOOL_CALLS, Kind.TOOL_RESULTS, Kind.IMAGE))
            assertTrue(parts.getOrDefault(kind.name(), 0L) > 0);
        assertEquals((long) input.estimatedInputTokens() + 25, tokens(read()));
        assertFalse(mapper.writeValueAsString(snapshot(input)).contains("工具返回的证据"), "持久快照只有分类数字，没有提示词或工具正文");
    }

    @Test void liveCompletionReleaseAndRestartKeepOneRequestAndResponseSnapshot() throws Exception {
        Turn current = turn();
        jdbc.update("UPDATE ai_chat_turn SET status = 'RUNNING', finished_at = NULL WHERE id = ?", current.id());
        var handle = wiring.handles().acquire(current.requestId(), sessionId).orElseThrow();
        var input = audit("实际请求");
        handle.modelContext(snapshot(input));
        handle.modelContextOutputTokens(500);
        assertEquals((long) input.estimatedInputTokens() + 500, tokens(read()));
        handle.modelContextOutputTokens(700);
        var live = read();
        assertEquals(true, live.get("active"));
        save(current, handle.modelContext());
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED', finished_at = ? WHERE id = ?", "2026-11-16T12:00:00Z", current.id());
        assertEquals(categories(live), categories(read()), "终态不改用整轮正文重算输出");
        wiring.handles().release(current.requestId(), handle);
        assertEquals(categories(live), categories(read()));
        var restarted = new SessionContextOccupancyService(Schema9Fixture.dataSource(root), wiring.turnService(), mapper);
        assertEquals(read(), restarted.read(sessionId));
    }

    @Test void aNewModelStepReplacesTheInputAndDoesNotReappendAnEarlierResponse() throws Exception {
        Turn current = turn();
        jdbc.update("UPDATE ai_chat_turn SET status = 'RUNNING', finished_at = NULL WHERE id = ?", current.id());
        var handle = wiring.handles().acquire(current.requestId(), sessionId).orElseThrow();
        handle.modelContext(snapshot(audit("第一份请求")));
        handle.modelContextOutputTokens(80);
        var continuation = audit(Map.of("model", "fixture", "messages", List.of(
                Map.of("role", "user", "content", "第一份请求"),
                Map.of("role", "assistant", "content", "已回答的前半句"),
                Map.of("role", "user", "content", "从这里继续"))), "CHAT");
        handle.modelContext(snapshot(continuation));
        assertEquals((long) continuation.estimatedInputTokens(), tokens(read()), "前半句已在续接请求中，不继续当作新输出叠加");
        handle.modelContextOutputTokens(12);
        assertEquals((long) continuation.estimatedInputTokens() + 12, tokens(read()));
        wiring.handles().release(current.requestId(), handle);
    }

    @Test void shrinkingActualRequestsCanLowerOccupancyWithoutDeletingLocalHistory() throws Exception {
        Turn first = turn();
        save(first, snapshot(audit("大块工具或检索内容".repeat(1000))).withOutput(100));
        long before = tokens(read());
        Turn second = turn();
        save(second, snapshot(audit("新的较小请求")).withOutput(20));
        assertTrue(tokens(read()) < before, "圆环如实反映实际请求变化，不强行维持单调增长");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(second.requestId(), read().get("requestId"));
    }

    @Test void anOlderTurnRetriedInPlaceSuppliesTheCurrentActualFrame() throws Exception {
        Turn first = turn(), later = turn();
        save(later, snapshot(audit("较晚的请求")).withOutput(100));
        jdbc.update("UPDATE ai_chat_turn SET status = 'RUNNING', finished_at = NULL WHERE id = ?", first.id());
        var handle = wiring.handles().acquire(first.requestId(), sessionId).orElseThrow();
        var input = audit("较早轮次的原地重试");
        handle.modelContext(snapshot(input).withOutput(9));
        assertEquals(first.requestId(), read().get("requestId"));
        assertEquals((long) input.estimatedInputTokens() + 9, tokens(read()));
        save(first, handle.modelContext());
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED', finished_at = ? WHERE id = ?", "2026-11-16T12:00:00Z", first.id());
        wiring.handles().release(first.requestId(), handle);
        assertEquals(first.requestId(), read().get("requestId"));
    }

    @Test void clearingEditingAndChangingGenerationInvalidateAnOldFrame() throws Exception {
        Turn current = turn();
        save(current, snapshot(audit("实际请求")).withOutput(12));
        assertNotNull(read());
        wiring.metadata().bumpHistoryRevision(sessionId);
        assertNull(read(), "修改后的会话还没有发送，不能继续宣称旧快照是新模型上下文");
        save(current, snapshot(audit("更新后的实际请求")));
        assertNotNull(read());
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = ? WHERE id = ?", "2026-11-16T12:00:00Z", current.id());
        assertNull(read());
        turn(sessionId, 1, "新问答");
        wiring.metadata().resetContextGeneration(sessionId);
        assertNull(read());
    }

    @Test void anotherSessionsFrameIsNeverUsed() throws Exception {
        long other = wiring.sessions().create("其它会话", null);
        Turn foreign = turn(other, 1, "另一会话");
        save(foreign, snapshot(audit("另一份实际请求")).withOutput(100000));
        assertNull(read());
    }

    @Test void corruptUnknownAndNegativeMetadataRemainUnavailable() throws Exception {
        Turn current = turn();
        String valid = mapper.writeValueAsString(Map.of("modelContext", snapshot(audit("请求"))));
        for (String bad : List.of("not-json", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":99"),
                valid.replace("\"outputTokens\":0", "\"outputTokens\":-1"),
                valid.replace("\"SYSTEM\"", "\"FUTURE_KIND\""),
                valid.replace(TokenEstimator.VERSION, "future-estimator"))) {
            jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?", bad, current.id());
            assertNull(read(), "损坏快照不能回退成全库统计");
        }
    }

    @Test void totalCanExceedTheWindowAndIntegerRangeWithoutClamping() throws Exception {
        Turn current = turn();
        var category = new RequestContextAudit.Category(Kind.OTHER, Integer.MAX_VALUE, 1,
                List.of(new RequestContextAudit.Item("envelope", null, Integer.MAX_VALUE)));
        var input = new RequestContextAudit(1, TokenEstimator.VERSION, "fixture", "CHAT", Integer.MAX_VALUE, 128000, List.of(category), false);
        save(current, snapshot(input).withOutput(Integer.MAX_VALUE));
        assertEquals(4294967294L, tokens(read()));
        categories(read());
    }

    private void request(Turn turn, RequestContextAudit audit, Integer output) throws Exception {
        String attempt = UuidV7.generate(System.currentTimeMillis() + ++sequence);
        String now = Instant.parse("2026-11-15T12:00:00Z").plusSeconds(sequence).toString();
        wiring.usage().reserve(new UsageRepository.Attempt(attempt, turn.requestId(), "CHAT", "fixture",
                "2026-11-15", "Asia/Singapore", 0, now, null));
        wiring.usage().markSent(attempt);
        assertTrue(wiring.usage().recordContextBreakdown(attempt, mapper.writeValueAsString(audit)));
        if (output != null) wiring.usage().settle(attempt, new UsageRepository.ActualUsage("provider", 100, output, 0, 0, true), now);
    }

    @Test void legacyFallbackUsesOnlyTheLatestMainRequestAndItsOwnReportedOutput() throws Exception {
        Turn current = turn();
        var main = audit("主模型实际输入");
        request(current, main, 7);
        request(current, audit(Map.of("model", "fixture", "messages", List.of(Map.of("role", "user", "content", "独立搜索请求"))), "NATIVE_WEB"), 10000);
        assertEquals((long) main.estimatedInputTokens() + 7, tokens(read()));
        assertEquals(true, read().get("complete"));
        assertEquals(read(), read(), "联网子请求不顶替主模型窗口，也不把各次输入相加");
    }

    @Test void missingLegacyOutputKeepsAnExplicitLowerBound() throws Exception {
        Turn current = turn();
        var main = audit("已知主模型输入");
        request(current, main, null);
        assertEquals((long) main.estimatedInputTokens(), tokens(read()));
        assertEquals(false, read().get("complete"));
    }
}
