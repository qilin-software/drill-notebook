package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiContextSettings;
import com.drillnotebook.app.model.AiRetrySettings;
import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiRuntimeSettingsRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Final protocol bytes, persisted accounting, cancellation, and retry through the production context stack. */
class ContextCompactionChatPathTest {
    private static final String ANSWER = "按最新要求继续整理计划。";
    private static final String EVIDENCE = "已读取的跨轮资料证据";
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> mainBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger summaries = new AtomicInteger();
    private final AtomicInteger toolExecutions = new AtomicInteger();
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Managed managed;
    private AiService ai;
    private AiRuntimeSettingsRepository runtime;
    private HttpServer server;
    private long session;
    private int sequence;
    private boolean rejectFirstMain;
    private boolean issueOneTool;
    private String toolName = "read_current_page";

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("context-chat-v083-");
        DataSource source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        managed = ChatContextWiring.wireManaged(source, jdbc, wiring);
        session = wiring.sessions().create("上下文验证", "fixture");
        ai = managed.attach(new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(), json,
                null, wiring.turnService(), wiring.cipher(), managed.stack().contexts()));
        ai.setContextOccupancy(new SessionContextOccupancyService(source, wiring.turnService(), json));
        runtime = new AiRuntimeSettingsRepository(jdbc);
        ai.setRuntimeSettings(runtime);
        configureRuntime(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        config("chat_completions", false, false);
    }

    @AfterEach void cleanup() throws Exception {
        SessionExecution.CURRENT.remove();
        if (server != null) server.stop(0);
        if (managed != null) managed.stack().indexes().shutdown();
        TestWorkspace.deleteRecursively(root);
    }

    @ParameterizedTest @CsvSource({"chat_completions,false,false", "chat_completions,true,true", "anthropic,false,true"})
    void compactionUsesTheAcceptedProtocolAndRecordsItsUsageWithoutReplacingMainOccupancy(String format, boolean upstreamStream, boolean uiStream) throws Exception {
        history(8, 500);
        config(format, upstreamStream, false);
        var body = body("改为仅整理计划，禁止写入");
        var events = new ArrayList<Map<String, Object>>();
        Map<String, Object> result = uiStream ? ai.chatStream(body, (type, text) -> true, events::add).resultView() : ai.chat(body);
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(ANSWER, result.get("reply"));
        assertTrue(summaries.get() > 0);
        assertEquals(1, mainBodies.size());
        assertTrue(mainBodies.get(0).contains("会话续接摘要"));
        assertTrue(mainBodies.get(0).contains("改为仅整理计划，禁止写入"));
        assertFalse(mainBodies.get(0).contains("_context"));
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
        var audit = json.valueToTree(managed.stack().inspector().audit((String) body.get("requestId"))).path("audit");
        String summaryKey = null;
        for (var selected : audit.path("selected")) if ("CONTINUATION".equals(selected.path("kind").asText())) summaryKey = selected.path("sourceKey").asText();
        assertNotNull(summaryKey, "实际发送审计必须提供摘要来源入口");
        var preview = managed.stack().inspector().preview((String) body.get("requestId"), List.of(summaryKey)).get(0);
        assertEquals(true, preview.get("available"));
        assertTrue(String.valueOf(preview.get("text")).contains("仅整理计划，禁止写入"));
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='COMPLETED'", Integer.class));
        assertEquals(summaries.get(), jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE purpose='CONTEXT_COMPACT' AND settled_at IS NOT NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE purpose='CHAT' AND settled_at IS NOT NULL", Integer.class));
        var usage = ai.sessionContextUsage(session);
        var current = (Map<?, ?>) usage.get("current");
        JsonNode mainAudit = lastMainAudit();
        assertEquals(mainAudit.path("estimatedInputTokens").asLong() + 20, ((Number) current.get("tokens")).longValue());
        assertEquals(8000, ((Number) current.get("contextWindow")).intValue());
        assertTrue(((Number) current.get("tokens")).longValue() <= 7200);
        var cumulative = (Map<?, ?>) usage.get("cumulative");
        assertEquals(summaries.get() * 100L + 20, ((Number) cumulative.get("outputTokens")).longValue());
        assertEquals(summaries.get() * 1000L + 2000, ((Number) cumulative.get("inputTokens")).longValue());
        assertTrue(jdbc.queryForList("SELECT context_breakdown FROM ai_usage_attempt WHERE purpose='CONTEXT_COMPACT'", String.class)
                .stream().allMatch(value -> value != null && value.contains("CONTEXT_COMPACT")));
        if (uiStream) {
            assertTrue(events.stream().anyMatch(event -> "compacting".equals(event.get("phase"))));
            assertTrue(events.stream().anyMatch(event -> Map.of("running", false).equals(event.get("contextCompaction"))));
        }
    }

    @Test void aRejectedMainRequestRetriesTheIdenticalFrozenBodyAfterCompaction() throws Exception {
        history(8, 500);
        rejectFirstMain = true;
        var body = body("只整理，不归档");
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(2, mainBodies.size());
        assertEquals(mainBodies.get(0), mainBodies.get(1), "HTTP 重试不能重新组装历史或再发送一轮提问");
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id=?", Integer.class, body.get("requestId")));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
    }

    @Test void stoppingFromCompactionProgressPreventsMainSendAndReleasesTheActiveTurn() throws Exception {
        history(8, 500);
        var body = body("开始整理");
        String requestId = (String) body.get("requestId");
        var stopped = new AtomicInteger();
        assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(body, (type, text) -> true, event -> {
            if (!"compacting".equals(event.get("phase"))) return;
            var status = wiring.turnService().status(requestId, "");
            assertEquals(Map.of("running", true), status.get("contextCompaction"));
            assertEquals("compacting", status.get("phase"));
            stopped.incrementAndGet();
            wiring.turnService().cancel(requestId);
        }));
        assertEquals(1, stopped.get());
        assertTrue(mainBodies.isEmpty());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='COMPLETED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='RUNNING'", Integer.class));
        assertEquals(false, wiring.turnService().status(requestId, "").get("active"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE settled_at IS NULL", Integer.class));
    }

    @Test void closedToolEvidenceSurvivesTheNextRealChatRequestWithoutReexecutingTheTool() throws Exception {
        config("chat_completions", false, true);
        ai.setChatTools(List.of(new AiService.ChatTool() {
            public AiUpstreamClient.ToolDefinition definition() {
                return new AiUpstreamClient.ToolDefinition("read_current_page", "读取当前页", Map.of("type", "object", "properties", Map.of()));
            }
            public String resultKey() { return "items"; }
            public Map<String, Object> execute(ChatContextPlan plan, String args, boolean memory, String text) {
                toolExecutions.incrementAndGet();
                return Map.of("status", "OK", "items", List.of(Map.of("text", EVIDENCE)), "truncated", false);
            }
        }));
        issueOneTool = true;
        assertEquals("COMPLETED", ai.chat(body("先读取当前页")).get("status"));
        assertEquals("COMPLETED", ai.chat(body("继续依据已读到的资料回答")).get("status"));
        assertEquals(1, toolExecutions.get());
        assertEquals(3, mainBodies.size());
        JsonNode next = json.readTree(mainBodies.get(2));
        assertTrue(next.toString().contains(EVIDENCE));
        assertTrue(next.toString().contains("tool_call_id"));
        assertEquals(1, occurrences(mainBodies.get(2), ANSWER));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_context_entry WHERE state='CLOSED'", Integer.class));
        assertFalse(jdbc.queryForList("SELECT payload_cipher FROM ai_context_entry", String.class).toString().contains(EVIDENCE));
    }

    @Test void disablingCompactionRejectsAnOversizedViewWithoutAnyNetworkSend() throws Exception {
        history(8, 500);
        configureRuntime(false);
        var error = assertThrows(ChatTurnRejectException.class, () -> ai.chat(body("继续")));
        assertEquals("CONTEXT_TOO_LARGE", error.code());
        assertTrue(mainBodies.isEmpty());
        assertEquals(0, summaries.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt", Integer.class));
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='RUNNING'", Integer.class));
    }

    @Test void aConfirmedWriteRemainsConfirmedWhenThereIsNoCapacityToCloseItsContextStep() throws Exception {
        config("chat_completions", false, true);
        toolName = "create_material";
        ai.setChatTools(List.of(new AiService.ChatTool() {
            public AiUpstreamClient.ToolDefinition definition() {
                return new AiUpstreamClient.ToolDefinition("create_material", "保存资料", Map.of("type", "object", "properties", Map.of()));
            }
            public boolean writes() { return true; }
            public String resultKey() { return "items"; }
            public Map<String, Object> execute(ChatContextPlan plan, String args, boolean memory, String text) {
                toolExecutions.incrementAndGet();
                try {
                    var storage = wiring.ledger().read();
                    assertEquals(com.drillnotebook.app.repository.ChatStorageLedger.LimitResult.APPLIED,
                            wiring.ledger().updateLimits(storage.revision(), storage.usedHistory(), storage.attachmentLimitBytes()));
                } catch (Exception error) { throw new RuntimeException(error); }
                return Map.of("status", "CREATED", "items", List.of(Map.of("id", 1, "text", "已确认保存的资料")));
            }
        }));
        issueOneTool = true;
        var body = body("保存这份资料");
        var failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(body, (kind, text) -> true));
        assertEquals(1, toolExecutions.get());
        assertEquals(1, mainBodies.size());
        assertEquals(1, failure.toolActivities().size());
        assertEquals("CREATED", failure.toolActivities().get(0).outcome());
        assertEquals("FINISHED", failure.toolActivities().get(0).state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_context_entry WHERE state='CLOSED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='RUNNING'", Integer.class));
    }

    private void configureRuntime(boolean compact) {
        var base = AiRuntimeSettings.DEFAULTS;
        var settings = new AiRuntimeSettings(base.maxToolStages(), base.maxToolExecutions(), base.turnTimeoutMinutes(),
                base.requestTimeoutMinutes(), base.maxOutputTokens(), 512, 256, base.repeatedToolLimit(),
                new AiRetrySettings(1, 1, false, 1), new AiContextSettings(compact, 80));
        runtime.save(runtime.read().revision(), settings);
    }

    private void config(String format, boolean stream, boolean tools) {
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model", "context-fixture", "apiKey", "fixture-key", "apiFormat", format, "tools", tools, "streaming", stream,
                "contextWindow", 8000, "maxOutputTokens", 512));
    }

    private void history(int count, int repeat) {
        for (int i = 1; i <= count; i++) {
            var turn = wiring.turnService().begin(new ChatTurnService.BeginInput(id(), 2, session,
                    "历史问题" + i + "资料".repeat(repeat), "", List.of(), Map.of())).lease();
            wiring.turnService().finalizeTurn(turn, ChatTurnService.UpstreamReply.of("历史回答" + i + "结论".repeat(repeat), null, "stop"), null);
        }
    }

    private Map<String, Object> body(String text) {
        return Map.of("protocolVersion", 2, "requestId", id(), "sessionId", session,
                "memoryOptions", Map.of("use", false), "input", Map.of("userText", text));
    }
    private String id() { return UuidV7.generate(System.currentTimeMillis() + ++sequence); }

    private JsonNode lastMainAudit() throws Exception {
        JsonNode found = null;
        for (String value : jdbc.queryForList("SELECT context_breakdown FROM ai_usage_attempt WHERE purpose='CHAT' ORDER BY created_at,id", String.class)) {
            if (value != null) { var node = json.readTree(value); if ("CHAT".equals(node.path("kind").asText())) found = node; }
        }
        assertNotNull(found);
        return found;
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = json.readTree(raw);
            boolean summary = raw.contains("你负责压缩一段会话");
            boolean anthropic = exchange.getRequestURI().getPath().endsWith("/messages");
            boolean stream = request.path("stream").asBoolean();
            String answer = ANSWER;
            if (summary) {
                summaries.incrementAndGet();
                JsonNode messages = request.path("messages");
                JsonNode data = json.readTree(messageText(messages.get(messages.size() - 1).path("content")));
                answer = json.writeValueAsString(Map.of("goal", "继续整理计划", "constraints", List.of("仅整理计划，禁止写入"),
                        "decisions", List.of("工具资料只作参考"), "completed", List.of(), "pending", List.of("继续未完成工作"),
                        "unknown", List.of(), "sources", data.path("availableSources"), "receipts", data.path("requiredReceipts")));
            } else {
                mainBodies.add(raw);
                if (rejectFirstMain && mainBodies.size() == 1) {
                    send(exchange, 503, "application/json", "{\"error\":{\"message\":\"fixture overloaded\"}}");
                    return;
                }
            }
            boolean tool = !summary && issueOneTool && mainBodies.size() == 1;
            String payload;
            int input = summary ? 1000 : 2000, output = summary ? 100 : 20;
            if (anthropic) {
                payload = json.writeValueAsString(Map.of("type", "message", "role", "assistant", "content", List.of(Map.of("type", "text", "text", answer)),
                        "stop_reason", "end_turn", "usage", Map.of("input_tokens", input, "output_tokens", output, "cache_read_input_tokens", 0, "cache_creation_input_tokens", 0)));
            } else {
                var message = tool ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "ctx-call-1", "type", "function",
                        "function", Map.of("name", toolName, "arguments", "{}")))) : Map.of("role", "assistant", "content", answer);
                payload = json.writeValueAsString(Map.of("choices", List.of(Map.of(stream ? "delta" : "message", message, "finish_reason", tool ? "tool_calls" : "stop")),
                        "usage", Map.of("prompt_tokens", input, "completion_tokens", output, "prompt_tokens_details", Map.of("cached_tokens", summary ? 250 : 1000))));
                if (stream) payload = "data: " + payload + "\n\ndata: [DONE]\n\n";
            }
            send(exchange, 200, stream ? "text/event-stream" : "application/json", payload);
        } finally { exchange.close(); }
    }

    private static String messageText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        var text = new StringBuilder();
        if (content.isArray()) content.forEach(block -> text.append(block.path("text").asText()));
        return text.toString();
    }
    private static void send(HttpExchange exchange, int status, String type, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private static int occurrences(String text, String part) { return text.split(java.util.regex.Pattern.quote(part), -1).length - 1; }
}
