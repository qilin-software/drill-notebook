package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiContextSettings;
import com.drillnotebook.app.model.AiRetrySettings;
import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextCompaction;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiRuntimeSettingsRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.SessionDispatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Final protocol bytes, persisted accounting, cancellation, and retry through the production context stack. */
class ContextCompactionChatPathTest {
    private static final String ANSWER = "按最新要求继续整理计划。";
    private static final String EVIDENCE = "已读取的跨轮资料证据";
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> mainBodies = new CopyOnWriteArrayList<>();
    private final List<String> summaryBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger summaries = new AtomicInteger();
    private final AtomicInteger toolExecutions = new AtomicInteger();
    private Path root;
    private JdbcTemplate jdbc;
    private DataSource source;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Managed managed;
    private AiService ai;
    private AiRuntimeSettingsRepository runtime;
    private HttpServer server;
    private long session;
    private int sequence;
    private boolean rejectFirstMain;
    private boolean issueOneTool;
    private int summaryFailures;
    private int summaryFailureStart = 1;
    private boolean invalidSummary;
    private int invalidSummaryResponses;
    private int summaryCharLimit;
    private int mainOverflows;
    private int mainOverflowStart = 1;
    private boolean overflowAsEvent;
    private boolean outputBeforeOverflow;
    private int truncatedSummaries;
    private SessionDispatchService dispatcher;
    private CountDownLatch summaryEntered;
    private CountDownLatch summaryRelease;
    private String toolName = "read_current_page";

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("context-chat-v083-");
        source = Schema9Fixture.dataSource(root);
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
        if (summaryRelease != null) summaryRelease.countDown();
        if (dispatcher != null) dispatcher.close();
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
            assertTrue(events.stream().anyMatch(event -> new ContextCompaction(false, true).equals(event.get("contextCompaction"))));
            assertEquals(1, events.stream().filter(event -> new ContextCompaction(true, false).equals(event.get("contextCompaction"))).count(),
                    "多段摘要共用一个连续运行状态，不能在分段之间闪回回答状态");
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

    @Test void aMainContextRefusalCompactsAndRetriesWithinTheOriginalTurn() throws Exception {
        history(2, 400);
        mainOverflows = 1;
        var request = body("继续整理，禁止写入");
        var result = ai.chat(request);
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(2, mainBodies.size());
        assertFalse(mainBodies.get(0).contains("会话续接摘要"));
        assertTrue(mainBodies.get(1).contains("会话续接摘要"));
        assertTrue(mainBodies.get(1).contains("继续整理，禁止写入"));
        assertTrue(mainBodies.get(1).length() < mainBodies.get(0).length());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id=?", Integer.class, request.get("requestId")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE settled_at IS NULL", Integer.class));
    }

    @Test void contextRefusalWithNoCompressibleHistoryDoesNotResendOrDisappear() throws Exception {
        mainOverflows = 10;
        var request = body("唯一的当前要求必须保留");
        var error = assertThrows(ChatTurnRejectException.class, () -> ai.chat(request));
        assertEquals("CONTEXT_TOO_LARGE", error.code());
        assertEquals(1, mainBodies.size());
        assertEquals(0, summaries.get());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ai_chat_turn WHERE request_id=?", String.class, request.get("requestId")));
    }

    @Test void disabledCompactionDoesNotOverrideTheUserSettingOnProviderRefusal() throws Exception {
        history(2, 200); configureRuntime(false); mainOverflows = 1;
        assertThrows(RuntimeException.class, () -> ai.chat(body("继续")));
        assertEquals(1, mainBodies.size()); assertEquals(0, summaries.get());
    }

    @Test void aStreamingContextRefusalBeforeAnyDeltaCanRecoverButPartialOutputCannotBeReplayed() throws Exception {
        history(2, 400); config("chat_completions", true, false);
        mainOverflows = 1; overflowAsEvent = true;
        var result = ai.chatStream(body("继续整理"), (type, text) -> true);
        assertEquals("COMPLETED", result.status());
        assertEquals(2, mainBodies.size()); assertTrue(summaries.get() > 0);
        int beforeMain = mainBodies.size(), beforeSummary = summaries.get();
        outputBeforeOverflow = true; mainOverflows = 1;
        var interrupted = ai.chatStream(body("继续原任务"), (type, text) -> true);
        assertEquals("保留已输出内容。", interrupted.text());
        assertNotEquals("COMPLETED", interrupted.status());
        assertEquals(beforeMain + 1, mainBodies.size()); assertEquals(beforeSummary, summaries.get());
    }

    @Test void aRefusalAfterAConfirmedWriteResumesWithItsToolResultWithoutExecutingItAgain() throws Exception {
        history(2, 400); config("chat_completions", false, true);
        toolName = "create_material"; issueOneTool = true; mainOverflowStart = 2; mainOverflows = 1;
        ai.setChatTools(List.of(new AiService.ChatTool() {
            public AiUpstreamClient.ToolDefinition definition() { return new AiUpstreamClient.ToolDefinition("create_material", "保存资料", Map.of("type", "object", "properties", Map.of())); }
            public boolean writes() { return true; }
            public String resultKey() { return "items"; }
            public Map<String, Object> execute(ChatContextPlan plan, String args, boolean memory, String text) {
                toolExecutions.incrementAndGet(); return Map.of("status", "CREATED", "items", List.of(Map.of("id", 1)));
            }
        }));
        assertEquals("COMPLETED", ai.chat(body("保存资料，然后整理" )).get("status"));
        assertEquals(1, toolExecutions.get()); assertEquals(3, mainBodies.size());
        assertTrue(mainBodies.get(2).contains("CREATED")); assertTrue(mainBodies.get(2).contains("ctx-call-1"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_context_entry WHERE state='CLOSED'", Integer.class));
    }

    @Test void repeatedProviderRefusalsHaveABoundedRecoveryBudget() throws Exception {
        history(8, 500); mainOverflows = 100;
        assertThrows(RuntimeException.class, () -> ai.chat(body("继续")));
        assertTrue(mainBodies.size() <= 3);
        assertTrue(summaries.get() <= ContextCoordinator.MAX_PASSES * 3);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='RUNNING'", Integer.class));
    }

    @Test void aPreOutputStreamingRefusalDoesNotSpendTheReservedAnswerBudget() throws Exception {
        history(2, 400); config("chat_completions", true, false); mainOverflows = 1; overflowAsEvent = true;
        var base = runtime.read().values();
        runtime.save(runtime.read().revision(), new AiRuntimeSettings(base.maxToolStages(), base.maxToolExecutions(),
                base.turnTimeoutMinutes(), base.requestTimeoutMinutes(), 1024, 512, 256, base.repeatedToolLimit(), base.retry(), base.context()));
        assertEquals("COMPLETED", ai.chatStream(body("继续整理"), (type, text) -> true).status());
        assertEquals(2, mainBodies.size());
        assertEquals(512, json.readTree(mainBodies.get(1)).path("max_tokens").asInt());
    }

    @ParameterizedTest @CsvSource({"chat_completions", "anthropic"})
    void aTruncatedSummaryGetsACompleteReplacementBeforeMainDispatch(String format) throws Exception {
        history(8, 500); config(format, false, false); truncatedSummaries = 1;
        var result = ai.chat(body("继续，保留当前要求"));
        assertEquals("COMPLETED", result.get("status"));
        assertTrue(summaryBodies.size() >= 2);
        assertTrue(summaryBodies.get(1).contains("输出长度限制被截断"));
        assertEquals(1, mainBodies.size());
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
    }

    @Test void compactionLeavesEnoughOfATightOutputBudgetToActuallySendTheAnswer() throws Exception {
        history(4, 500);
        var base = runtime.read().values();
        runtime.save(runtime.read().revision(), new AiRuntimeSettings(base.maxToolStages(), base.maxToolExecutions(),
                base.turnTimeoutMinutes(), base.requestTimeoutMinutes(), 1024, 512, 256, base.repeatedToolLimit(),
                base.retry(), base.context()));
        var result = ai.chat(body("继续整理，必须给出回答"));
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(ANSWER, result.get("reply"));
        assertFalse(summaryBodies.isEmpty());
        assertEquals(1, mainBodies.size(), "压缩不能耗尽或提前占住主回答的预算");
        assertTrue(json.readTree(mainBodies.get(0)).path("max_tokens").asInt() >= 256);
        assertTrue(((Number) ((Map<?, ?>) ai.sessionContextUsage(session).get("cumulative")).get("outputTokens")).longValue() <= 1024);
    }

    @Test void transientSummaryFailureRetriesTheSameSummaryAndThenSendsTheMainRequest() throws Exception {
        history(8, 500);
        summaryFailures = 1;
        var result = ai.chat(body("继续整理"));
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(ANSWER, result.get("reply"));
        assertTrue(summaryBodies.size() >= 2);
        assertEquals(summaryBodies.get(0), summaryBodies.get(1), "摘要重试使用冻结请求，不生成新一轮提问");
        assertEquals(1, mainBodies.size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE settled_at IS NULL", Integer.class));
    }

    @Test void aChildRequestDeadlineRetriesTheFrozenSummaryWithoutCancellingOrShorteningTheAnswer() throws Exception {
        history(4, 500);
        var childRequests = new ArrayList<String>();
        var mainTimeout = new AtomicInteger();
        ReflectionTestUtils.setField(ai, "upstreamClient", new AiUpstreamClient(HttpClient.newHttpClient(), json) {
            @Override public Result callPrepared(Upstream target, com.drillnotebook.app.model.PreparedRequest request,
                    StreamListener listener, int seconds, ActiveTurnRegistry.Handle handle,
                    java.util.function.Consumer<com.drillnotebook.app.model.PreparedRequest> beforeSend,
                    java.util.function.Consumer<com.drillnotebook.app.model.PreparedRequest> onSend) {
                if (request.serialized().contains("你负责压缩一段会话")) {
                    assertTrue(seconds <= AiCompactionBudget.MAX_REQUEST_SECONDS);
                    childRequests.add(request.serialized());
                    if (childRequests.size() == 1) {
                        if (beforeSend != null) beforeSend.accept(request);
                        handle.requestAttempted(request.context().kind());
                        if (onSend != null) onSend.accept(request);
                        return new Result("", "", "deadline", false, false, false, Usage.unknown());
                    }
                } else mainTimeout.set(seconds);
                assertFalse(handle.cancelRequested(), "子请求到期不能变成用户停止整轮");
                return super.callPrepared(target, request, listener, seconds, handle, beforeSend, onSend);
            }
        });
        var result = ai.chat(body("继续整理"));
        assertEquals("COMPLETED", result.get("status"));
        assertTrue(childRequests.size() >= 2);
        assertEquals(childRequests.get(0), childRequests.get(1));
        assertEquals(1, mainBodies.size());
        assertTrue(mainTimeout.get() > AiCompactionBudget.MAX_REQUEST_SECONDS);
        assertEquals(childRequests.size(), jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE purpose='CONTEXT_COMPACT' AND settled_at IS NOT NULL", Integer.class));
    }

    @Test void upstreamContextOverflowShrinksSummaryChunksAndStillSendsTheProtectedCurrentQuestion() throws Exception {
        history(4, 500);
        summaryCharLimit = 3500;
        var result = ai.chat(body("当前要求：只整理计划，不能保存"));
        assertEquals("COMPLETED", result.get("status"));
        assertTrue(summaryBodies.size() > 1);
        assertTrue(summaryBodies.stream().anyMatch(body -> body.length() > summaryCharLimit));
        assertTrue(summaryBodies.stream().anyMatch(body -> body.contains("fragments")));
        assertEquals(1, mainBodies.size());
        assertTrue(mainBodies.get(0).contains("当前要求：只整理计划，不能保存"));
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
    }

    @Test void followingShortRoundsReuseTheCheckpointAndKeepTheCompletedMarkerOnItsOriginalRound() throws Exception {
        history(4, 500);
        var first = ai.chat(body("继续整理"));
        int before = summaries.get();
        assertTrue(before > 0);
        for (int round = 0; round < 3; round++) {
            var result = ai.chat(body("下一步" + round));
            assertEquals("COMPLETED", result.get("status"));
            assertEquals(ContextCompaction.NONE, result.get("contextCompaction"));
        }
        assertEquals(before, summaries.get(), "已有有效摘要与近期原文仍能放入窗口时不反复压缩");
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
        assertEquals(new ContextCompaction(false, true), first.get("contextCompaction"));
        var messages = json.valueToTree(managed.stack().history().page(session, null, null, 50)).path("messages");
        int markers = 0;
        for (var message : messages) if (message.path("contextCompaction").path("completed").asBoolean()) markers++;
        assertEquals(1, markers);
    }

    @Test void completedCompactionPersistsInTheFinalResultStatusAndReloadedHistory() throws Exception {
        history(4, 500);
        var body = body("继续整理");
        var result = ai.chatStream(body, (type, text) -> true).resultView();
        var completed = new ContextCompaction(false, true);
        assertEquals(completed, result.get("contextCompaction"));
        String requestId = (String) body.get("requestId");
        assertEquals(completed, wiring.turnService().status(requestId, "").get("contextCompaction"));
        assertEquals(completed, ChatLegacyWiring.wire(source, jdbc).turnService().status(requestId, "").get("contextCompaction"));
        var page = json.valueToTree(managed.stack().history().page(session, null, null, 50));
        JsonNode answer = null;
        for (var message : page.path("messages")) if (requestId.equals(message.path("requestId").asText()) && "assistant".equals(message.path("role").asText())) answer = message;
        assertNotNull(answer);
        assertTrue(answer.path("contextCompaction").path("completed").asBoolean());
        assertFalse(answer.path("contextCompaction").path("running").asBoolean());
        assertFalse(jdbc.queryForObject("SELECT result_meta_cipher FROM ai_chat_turn WHERE request_id=?", String.class, requestId).contains("contextCompaction"));
    }

    @Test void managedDispatchPublishesRunningSnapshotsAndTheDurableCompletedEvent() throws Exception {
        history(4, 500);
        var dispatch = dispatcher();
        summaryEntered = new CountDownLatch(1); summaryRelease = new CountDownLatch(1);
        var body = body("继续整理");
        String requestId = (String) body.get("requestId");
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        dispatch.submit(body);
        try (var subscription = dispatch.subscribe(requestId, (name, event) -> events.add(event))) {
            assertTrue(summaryEntered.await(10, TimeUnit.SECONDS));
            var running = json.valueToTree(dispatch.runtime(session)).path("current");
            assertTrue(running.path("contextCompaction").path("running").asBoolean());
            assertEquals("compacting", running.path("phase").asText());
            summaryRelease.countDown();
            var result = dispatch.await(requestId);
            assertEquals("COMPLETED", result.get("status"));
            assertEquals(new ContextCompaction(false, true), result.get("contextCompaction"));
            assertTrue(events.stream().anyMatch(event -> new ContextCompaction(false, true).equals(event.get("contextCompaction"))));
        }
    }

    @Test void managedDispatchKeepsTheCompactionFailureCodeAndDoesNotLeaveASpinnerOrRetryThePrompt() throws Exception {
        history(4, 500);
        summaryFailures = 99;
        var dispatch = dispatcher();
        var body = body("继续整理");
        String requestId = (String) body.get("requestId");
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        dispatch.submit(body);
        try (var subscription = dispatch.subscribe(requestId, (name, event) -> events.add(event))) {
            assertThrows(RuntimeException.class, () -> dispatch.await(requestId));
            assertTrue(events.stream().anyMatch(event -> "CONTEXT_COMPACTION_FAILED".equals(event.get("code"))));
            assertTrue(events.stream().filter(event -> event.containsKey("code")).allMatch(event -> ContextCompaction.NONE.equals(event.get("contextCompaction"))));
            assertEquals(2, summaryBodies.size());
            assertTrue(mainBodies.isEmpty());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id=?", Integer.class, requestId));
        }
    }

    private SessionDispatchService dispatcher() {
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("ai", ai);
        dispatcher = new SessionDispatchService(beans.getBeanProvider(AiService.class),
                new SessionDispatchRepository(jdbc, new ImmediateTransaction(source)), wiring.sessions(), wiring.turnService(),
                managed.stack().vault(), wiring.cipher(), jdbc, json);
        return dispatcher;
    }

    @Test void exhaustedSummaryRetriesEndVisiblyWithoutRetryingTheCoordinatorOrPretendingSuccess() throws Exception {
        history(4, 500);
        summaryFailures = 99;
        var body = body("继续整理");
        var events = new ArrayList<Map<String, Object>>();
        var failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(body, (type, text) -> true, events::add));
        assertEquals("CONTEXT_COMPACTION_FAILED", failure.code());
        assertEquals(2, summaryBodies.size());
        assertTrue(mainBodies.isEmpty());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertFalse(failure.contextCompaction().completed());
        assertFalse(events.stream().anyMatch(event -> new ContextCompaction(false, true).equals(event.get("contextCompaction"))));
        assertEquals(ContextCompaction.NONE, wiring.turnService().status((String) body.get("requestId"), "").get("contextCompaction"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='RUNNING'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE settled_at IS NULL", Integer.class));
    }

    @Test void aUsableCheckpointFallbackClearsTheSummaryFailureBeforeReturningToTheAnswerPhase() throws Exception {
        history(4, 425);
        summaryFailureStart = 2;
        summaryFailures = 99;
        var body = body("继续");
        String requestId = (String) body.get("requestId");
        var recoveryAtEnd = new ArrayList<Object>();
        var result = ai.chatStream(body, (type, text) -> true, event -> {
            if (new ContextCompaction(false, true).equals(event.get("contextCompaction"))) {
                recoveryAtEnd.add(wiring.turnService().recovery(requestId, "").orElse(null));
            }
        }).resultView();
        assertEquals(3, summaryBodies.size());
        assertEquals(1, mainBodies.size());
        assertEquals(1, recoveryAtEnd.size());
        assertNull(recoveryAtEnd.get(0), "返回正常回答阶段时不能残留旧摘要的失败提示");
        assertEquals(new ContextCompaction(false, true), result.get("contextCompaction"));
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
    }

    @Test void anInvalidSummaryEndsWithoutAFalseCompletedMarker() throws Exception {
        history(4, 500);
        invalidSummary = true;
        var body = body("继续整理");
        var failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(body, (type, text) -> true));
        assertEquals("CONTEXT_SUMMARY_INVALID", failure.code());
        assertEquals(2, summaryBodies.size());
        assertTrue(mainBodies.isEmpty());
        assertFalse(failure.contextCompaction().completed());
        assertEquals(com.drillnotebook.app.model.AiRequestDiagnostics.Stage.COMPACTING, failure.failure().diagnostics().stage());
        assertFalse(failure.failure().diagnostics().mainRequestAttempted());
        assertTrue(failure.failure().diagnostics().compactionRequestAttempted(), "The summary was dispatched even though the main request was not");
        assertTrue(managed.checkpoints().read(session).isEmpty());
        var status = json.valueToTree(wiring.turnService().status((String) body.get("requestId"), ""));
        assertEquals("CONTEXT_SUMMARY_INVALID", status.path("failure").path("code").asText());
        assertEquals("saved", status.path("persistenceStatus").asText());
        var history = new ChatHistoryService(new com.drillnotebook.app.repository.ChatHistoryRepository(source),
                wiring.metadata(), wiring.cipher(), managed.stack().vault());
        var latest = json.valueToTree(history.page(session, null, null, 2)).path("messages");
        assertEquals(2, latest.size());
        assertEquals("assistant", latest.get(1).path("role").asText());
        assertEquals("CONTEXT_SUMMARY_INVALID", latest.get(1).path("failure").path("code").asText());
    }

    @ParameterizedTest @CsvSource({"chat_completions,false", "anthropic,true"})
    void aMalformedFirstSummaryIsRepairedOnceThenTheOriginalRequestContinues(String format, boolean uiStream) throws Exception {
        history(4, 500);
        config(format, false, false);
        invalidSummaryResponses = 1;
        var request = body("继续整理，保留原资料");
        var result = uiStream ? ai.chatStream(request, (type, text) -> true).resultView() : ai.chat(request);
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(ANSWER, result.get("reply"));
        assertTrue(summaryBodies.size() >= 2);
        assertEquals(1, summaryBodies.stream().filter(value -> value.contains("上一次摘要没有通过格式校验")).count());
        assertTrue(summaryBodies.get(1).contains("上一次摘要没有通过格式校验"));
        var firstMessages = json.readTree(summaryBodies.get(0)).path("messages");
        var repairedMessages = json.readTree(summaryBodies.get(1)).path("messages");
        assertEquals(messageText(firstMessages.get(firstMessages.size() - 1).path("content")),
                messageText(repairedMessages.get(repairedMessages.size() - 1).path("content")), "修复摘要不能截掉或换掉原始来源");
        assertEquals(1, mainBodies.size());
        assertEquals(new ContextCompaction(false, true), result.get("contextCompaction"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id=?", Integer.class, request.get("requestId")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE settled_at IS NULL", Integer.class));
    }

    @Test void preparationFailureBeforeAnyRequestKeepsTheLastActualWindowAndASavedError() throws Exception {
        var first = body("第一次正常请求");
        ai.chat(first);
        var occupancy = new SessionContextOccupancyService(source, wiring.turnService(), json);
        var frame = occupancy.read(session);
        assertNotNull(frame);
        var next = new java.util.LinkedHashMap<>(body("第二次准备失败"));
        next.put("pageContext", Map.of("kind", "page", "pageId", "invalid-id"));
        var error = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(next, (type, text) -> true));
        assertEquals("PAGE_CONTEXT_INVALID", error.code());
        assertEquals(1, mainBodies.size(), "失败准备不会重新发送原提示词或调用主模型");
        assertTrue(summaryBodies.isEmpty());
        assertEquals(frame.get("requestId"), occupancy.read(session).get("requestId"));
        assertEquals(frame.get("tokens"), occupancy.read(session).get("tokens"));
        var status = json.valueToTree(wiring.turnService().status((String) next.get("requestId"), ""));
        assertEquals("FAILED", status.path("status").asText());
        assertEquals("PAGE_CONTEXT_INVALID", status.path("failure").path("code").asText());
        assertEquals("saved", status.path("persistenceStatus").asText());
        assertFalse(status.path("active").asBoolean());
        assertEquals("PREPARING", status.path("failure").path("diagnostics").path("stage").asText());
        assertFalse(status.path("failure").path("diagnostics").path("mainRequestAttempted").asBoolean());
        assertFalse(status.path("failure").path("diagnostics").path("compactionRequestAttempted").asBoolean());
        assertTrue(error.getMessage().contains("主模型请求尚未发出"));
    }

    @Test void invalidHttpRequestDoesNotCreateASentAttemptOrReplaceTheLastWindow() throws Exception {
        ai.chat(body("先保存一次真实窗口"));
        var occupancy = new SessionContextOccupancyService(source, wiring.turnService(), json);
        var previous = occupancy.read(session);
        jdbc.update("UPDATE ai_config SET endpoint=? WHERE purpose='chat'", "http://[invalid");
        var request = body("地址无法构建，主请求不应发送");
        var failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(request, (kind, text) -> true));
        assertFalse(failure.failure().diagnostics().mainRequestAttempted());
        assertEquals(1, mainBodies.size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE operation_id=?", Integer.class, request.get("requestId")));
        assertNotNull(occupancy.read(session));
        assertEquals(previous.get("requestId"), occupancy.read(session).get("requestId"));
        assertEquals(previous.get("tokens"), occupancy.read(session).get("tokens"));
    }

    @Test void cancellationAtTheFinalDispatchBoundaryKeepsThePlanUnsent() throws Exception {
        var request = body("在发出前停止");
        var events = new AtomicInteger();
        var result = ai.chatStream(request, (kind, text) -> true, event -> {
            if (events.incrementAndGet() == 2)
                wiring.turnService().cancelActive((String) request.get("requestId"));
        }).resultView();
        assertEquals("ABORTED", result.get("status"));
        assertEquals("not_sent", json.valueToTree(result).path("contextAudit").path("dispatchState").asText());
        assertTrue(mainBodies.isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE operation_id=?", Integer.class, request.get("requestId")));
    }

    @Test void contextChangesAtTheFinalDispatchBoundaryPreventTheHttpSend() throws Exception {
        var request = body("发出之前再次检查来源");
        var events = new AtomicInteger();
        var failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(request, (kind, text) -> true, event -> {
            if (events.incrementAndGet() == 2)
                jdbc.update("UPDATE ai_chat_session SET history_revision=history_revision+1 WHERE id=?", session);
        }));
        assertEquals("CONTEXT_CHANGED", failure.code());
        assertFalse(failure.failure().diagnostics().mainRequestAttempted());
        assertTrue(mainBodies.isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE operation_id=?", Integer.class, request.get("requestId")));
    }

    @Test void changedContextBeforeSendIsFinalizedOnceWithTheSpecificError() throws Exception {
        var request = body("保留这个待回答问题");
        var updated = new java.util.concurrent.atomic.AtomicBoolean();
        var error = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(request, (kind, text) -> true, event -> {
            if (updated.compareAndSet(false, true)) jdbc.update("UPDATE ai_chat_session SET history_revision=history_revision+1 WHERE id=?", session);
        }));
        assertEquals("CONTEXT_CHANGED", error.code());
        assertTrue(mainBodies.isEmpty());
        assertTrue(summaryBodies.isEmpty());
        var status = json.valueToTree(wiring.turnService().status((String) request.get("requestId"), ""));
        assertEquals("FAILED", status.path("status").asText());
        assertEquals("saved", status.path("persistenceStatus").asText());
        assertEquals("CONTEXT_CHANGED", status.path("failure").path("code").asText());
        assertFalse(status.path("failure").path("diagnostics").path("mainRequestAttempted").asBoolean());
        assertFalse(status.path("active").asBoolean());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class), "One user message and one stable failed assistant identity are retained");
    }

    @Test void requiredCompactionPublicationFailureIsLocalAndNeverInvokesTheMainModel() throws Exception {
        history(8, 500);
        jdbc.execute("CREATE TRIGGER fixture_refuse_checkpoint BEFORE INSERT ON ai_context_checkpoint "
                + "BEGIN SELECT RAISE(ABORT, 'fixture local write failure'); END");
        var request = body("继续整理");
        var error = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(request, (kind, text) -> true));
        assertEquals("CONTEXT_STORAGE_FAILED", error.code());
        assertTrue(error.getMessage().contains("本地上下文检查点"));
        assertTrue(error.getMessage().contains("主模型请求尚未发出"));
        assertFalse(summaryBodies.isEmpty());
        assertTrue(mainBodies.isEmpty());
        assertFalse(error.contextCompaction().completed());
        var restarted = ChatLegacyWiring.wire(source, jdbc);
        var status = json.valueToTree(restarted.turnService().status((String) request.get("requestId"), ""));
        assertEquals(json.valueToTree(error.failure()), status.path("failure"));
        assertEquals("COMPACTING", status.path("failure").path("diagnostics").path("stage").asText());
        assertFalse(status.path("failure").path("diagnostics").path("mainRequestAttempted").asBoolean());
        assertTrue(status.path("failure").path("diagnostics").path("compactionRequestAttempted").asBoolean());
        assertEquals("context_storage_failed", status.path("finishReason").asText());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='COMPLETED'", Integer.class));
    }

    @Test void aLocalReadFailureBeforeSendIsNotReportedAsAContextVersionChange() throws Exception {
        var request = body("继续处理原任务");
        var repository = org.mockito.Mockito.spy(wiring.metadata());
        org.mockito.Mockito.doThrow(new java.sql.SQLException("fixture local context read unavailable")).when(repository).read(session);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(request, (kind, text) -> true, event -> {
            if (changed.compareAndSet(false, true)) ReflectionTestUtils.setField(managed.stack().contexts(), "sessions", repository);
        }));
        assertEquals("CONTEXT_STORAGE_FAILED", failure.code());
        assertTrue(failure.getMessage().contains("fixture local context read unavailable"));
        assertFalse(failure.getMessage().contains("会话内容或授权已变化"));
        assertTrue(mainBodies.isEmpty());
        assertTrue(summaryBodies.isEmpty());
        var status = json.valueToTree(wiring.turnService().status((String) request.get("requestId"), ""));
        assertEquals("saved", status.path("persistenceStatus").asText());
        assertEquals(json.valueToTree(failure.failure()), status.path("failure"));
        assertEquals("PREPARING", status.path("failure").path("diagnostics").path("stage").asText());
        assertFalse(status.path("failure").path("diagnostics").path("mainRequestAttempted").asBoolean());
    }

    @Test void failureMetadataIsEncryptedBoundedAndRemovedWithItsSource() throws Exception {
        var lease = wiring.turnService().begin(new ChatTurnService.BeginInput(id(), 2, session,
                "隔离失败记录", "", List.of(), Map.of())).lease();
        var result = wiring.turnService().abandon(lease, "request_failed",
                new IllegalStateException("本地测试报错 api_key=fixture-hidden bearer fixture-bearer\n" + "细节".repeat(3000)));
        assertEquals("FAILED", result.status());
        assertEquals("saved", result.persistenceStatus());
        var restarted = ChatLegacyWiring.wire(source, jdbc);
        var status = json.valueToTree(restarted.turnService().status(lease.requestId(), ""));
        String message = status.path("failure").path("message").asText();
        assertTrue(message.contains("[已隐藏]"));
        assertFalse(message.contains("fixture-hidden"));
        assertFalse(message.contains("fixture-bearer"));
        assertTrue(message.length() <= 4001);
        var columns = wiring.turns().findResultMeta(lease.requestId()).orElseThrow();
        assertFalse(columns.cipher().contains("本地测试报错"));
        assertFalse(columns.crypto().contains("本地测试报错"));
        wiring.turns().clearContent(lease.requestId(), java.time.Instant.now().toString());
        assertTrue(json.valueToTree(restarted.turnService().status(lease.requestId(), "")).path("failure").isNull());
    }

    @Test void fiftyThousandTokenWindowWithLargeOutputReserveRepairsAndContinuesWithoutDroppingHistory() throws Exception {
        var base = AiRuntimeSettings.DEFAULTS;
        runtime.save(runtime.read().revision(), new AiRuntimeSettings(base.maxToolStages(), base.maxToolExecutions(),
                base.turnTimeoutMinutes(), base.requestTimeoutMinutes(), base.maxOutputTokens(), 16384, 4096,
                base.repeatedToolLimit(), new AiRetrySettings(1, 1, false, 1), new AiContextSettings(true, 80)));
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model", "context-fixture", "apiKey", "fixture-key", "apiFormat", "chat_completions", "tools", false,
                "streaming", false, "contextWindow", 50000, "maxOutputTokens", 16384));
        history(7, 1800);
        invalidSummaryResponses = 1;
        var request = body("把 IO 与 JDBC 整理成结构化清单");
        var result = ai.chatStream(request, (type, text) -> true).resultView();
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(ANSWER, result.get("reply"));
        assertFalse(summaryBodies.isEmpty());
        assertEquals(1, mainBodies.size());
        assertEquals(16, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(new ContextCompaction(false, true), result.get("contextCompaction"));
        assertEquals(request.get("requestId"), new SessionContextOccupancyService(source, wiring.turnService(), json).read(session).get("requestId"));
    }

    @Test void stoppingFromCompactionProgressPreventsMainSendAndReleasesTheActiveTurn() throws Exception {
        history(8, 500);
        var body = body("开始整理");
        String requestId = (String) body.get("requestId");
        var stopped = new AtomicInteger();
        assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(body, (type, text) -> true, event -> {
            if (!"compacting".equals(event.get("phase"))) return;
            var status = wiring.turnService().status(requestId, "");
            assertEquals(new ContextCompaction(true, false), status.get("contextCompaction"));
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
            boolean truncated = summary && truncatedSummaries-- > 0;
            String answer = ANSWER;
            if (summary) {
                summaries.incrementAndGet();
                summaryBodies.add(raw);
                if (summaryRelease != null) {
                    summaryEntered.countDown();
                    try {
                        if (!summaryRelease.await(10, TimeUnit.SECONDS)) throw new IOException("fixture summary gate timed out");
                    } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                }
                if (summaryBodies.size() >= summaryFailureStart && summaryFailures-- > 0) {
                    send(exchange, 503, "application/json", "{\"error\":{\"message\":\"summary fixture overloaded\"}}");
                    return;
                }
                if (summaryCharLimit > 0 && raw.length() > summaryCharLimit) {
                    send(exchange, 400, "application/json", "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"maximum context length exceeded\"}}");
                    return;
                }
                JsonNode messages = request.path("messages");
                JsonNode data = json.readTree(messageText(messages.get(messages.size() - 1).path("content")));
                answer = json.writeValueAsString(Map.of("goal", "继续整理计划", "constraints", List.of("仅整理计划，禁止写入"),
                        "decisions", List.of("工具资料只作参考"), "completed", List.of(), "pending", List.of("继续未完成工作"),
                        "unknown", List.of(), "sources", data.path("availableSources"), "receipts", data.path("requiredReceipts")));
                if (invalidSummary || invalidSummaryResponses-- > 0) answer = "not a checkpoint";
            } else {
                mainBodies.add(raw);
                if (mainBodies.size() >= mainOverflowStart && mainOverflows-- > 0) {
                    if (overflowAsEvent) {
                        String prefix = outputBeforeOverflow ? "data: {\"choices\":[{\"delta\":{\"content\":\"保留已输出内容。\"},\"finish_reason\":null}]}\n\n" : "";
                        send(exchange, 200, "text/event-stream", prefix + "event: error\ndata: {\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"maximum context length exceeded\"}}\n\n");
                        return;
                    }
                    send(exchange, 400, "application/json", "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"maximum context length exceeded\"}}");
                    return;
                }
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
                        "stop_reason", truncated ? "max_tokens" : "end_turn", "usage", Map.of("input_tokens", input, "output_tokens", output, "cache_read_input_tokens", 0, "cache_creation_input_tokens", 0)));
            } else {
                var message = tool ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "ctx-call-1", "type", "function",
                        "function", Map.of("name", toolName, "arguments", "{}")))) : Map.of("role", "assistant", "content", answer);
                payload = json.writeValueAsString(Map.of("choices", List.of(Map.of(stream ? "delta" : "message", message, "finish_reason", truncated ? "length" : tool ? "tool_calls" : "stop")),
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
