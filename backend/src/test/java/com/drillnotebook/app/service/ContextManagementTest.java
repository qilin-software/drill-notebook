package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiContextSettings;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextSource;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.UsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the injected production stack, not the old vault-summary-only fixture. */
class ContextManagementTest {
    private static final String PASSWORD = "context test password";
    private final ObjectMapper json = new ObjectMapper();
    private final AiUpstreamClient upstream = new AiUpstreamClient(null, json);
    private Path root;
    private DataSource source;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Managed managed;
    private long session;
    private int requestSequence;
    private AiUpstreamClient.Upstream target;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("context-v083-");
        source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        managed = ChatContextWiring.wireManaged(source, jdbc, wiring);
        session = ChatLegacyWiring.ensureSession(jdbc);
        target = new AiUpstreamClient.Upstream("https://example.invalid", "fixture-key", "fixture-model", "chat_completions", 512, true, false, false);
    }

    @AfterEach void cleanup() throws Exception {
        if (managed != null) managed.stack().indexes().shutdown();
        TestWorkspace.deleteRecursively(root);
    }

    @Test void retainsMoreThan32CompleteRoundsWithoutSyntheticMissingHistory() throws Exception {
        for (int i = 1; i <= 72; i++) complete("第" + i + "轮问题", "第" + i + "轮回答");
        var current = begin("继续原任务");
        var plan = plan(current, 32000);
        var view = prepare(plan, current, plan.messages(), 32000, (t, p) -> { fail("Small history does not need compaction"); return null; });
        assertEquals(72, view.messages().stream().map(ContextSource::from).filter(s -> s != null && s.kind().equals("TURN")).map(ContextSource::key).distinct().count());
        assertTrue(view.prepared().serialized().contains("第1轮问题"));
        assertTrue(view.prepared().serialized().contains("第72轮回答"));
        assertFalse(view.prepared().serialized().contains("_context"));
        assertFalse(view.compressed());
    }

    @Test void aLongCurrentQuestionCanUseTheWindowBeyondTheOldFixedLimits() throws Exception {
        String text = "当前提问资料".repeat(1800);
        var current = begin(text);
        var plan = plan(current, 32000);
        var view = prepare(plan, current, plan.messages(), 32000, this::summary);
        assertTrue(view.prepared().context().estimatedInputTokens() > 8000);
        assertTrue(view.prepared().serialized().contains(text));
        assertFalse(view.compressed());
        assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, this::summary));
    }

    @Test void compactedHistoryIsEncryptedAndOriginalMessagesAreUnchanged() throws Exception {
        history(8, 500);
        var current = begin("先不要归档，只整理计划");
        var plan = plan(current, 8000);
        long messages = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Long.class);
        long before = wiring.ledger().read().historyBytes();
        var view = prepare(plan, current, plan.messages(), 8000, this::summary);
        assertTrue(view.compressed());
        assertTrue(view.prepared().context().estimatedInputTokens() <= ContextWindowBudget.of(8000, 512, AiContextSettings.DEFAULTS).hardInput());
        assertEquals(messages, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Long.class));
        var checkpoint = managed.checkpoints().read(session).orElseThrow();
        assertEquals(1, checkpoint.revision());
        assertFalse(checkpoint.cipher().contains("不要归档"));
        assertEquals(before + checkpoint.bytes(), wiring.ledger().read().historyBytes());
        assertEquals("先不要归档，只整理计划", text(view.messages().get(view.messages().size() - 1).get("content")));
        assertTrue(view.checkpoint().sources().stream().anyMatch(s -> s.kind().equals("ANCHOR")));
    }

    @Test void multipleSummaryChunksPublishOnceAndKeepTheRecentTail() throws Exception {
        history(14, 250);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var chunks = new AtomicInteger();
        var view = prepare(plan, current, plan.messages(), 8000, (t, p) -> { chunks.incrementAndGet(); return summary(t, p); });
        assertTrue(chunks.get() > 1);
        assertEquals(1, managed.checkpoints().read(session).orElseThrow().revision());
        var remaining = view.messages().stream().map(ContextSource::from).filter(s -> s != null && s.kind().equals("TURN")).map(ContextSource::id).distinct().toList();
        assertTrue(remaining.size() >= 2);
        assertTrue(view.prepared().serialized().contains("历史问题14"));
        assertTrue(view.prepared().serialized().contains("历史回答13"));
        assertEquals(1, view.messages().stream().filter(m -> m.containsKey(ContextCoordinator.CHECKPOINT_TAG)).count());
    }

    @Test void oversizedRecentRoundsCanBeCompactedWithoutDroppingTheirSources() throws Exception {
        var turns = history(8, 800);
        var current = begin("保留已确认结论并继续");
        var plan = plan(current, 8000);
        var view = prepare(plan, current, plan.messages(), 8000, this::summary);
        assertTrue(view.compressed());
        assertTrue(view.checkpoint().sources().stream().anyMatch(s -> s.kind().equals("TURN") && s.turnId() == turns.get(turns.size() - 1).turnId()));
        assertTrue(view.prepared().context().estimatedInputTokens() <= ContextWindowBudget.of(8000, 512, AiContextSettings.DEFAULTS).targetInput());
        assertTrue(view.prepared().serialized().contains("保留已确认结论并继续"));
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED'", Integer.class));
    }

    @Test void aFailureInALaterChunkCannotPublishTheEarlierStagedSummary() throws Exception {
        history(14, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        long bytes = wiring.ledger().read().historyBytes();
        var chunks = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> prepare(plan, current, plan.messages(), 8000, (t, p) -> {
            if (chunks.incrementAndGet() == 2) throw new IllegalStateException("fixture summary failure");
            return summary(t, p);
        }));
        assertTrue(chunks.get() >= 2);
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(bytes, wiring.ledger().read().historyBytes());
    }

    @Test void aCheckpointStorageFailureReleasesTheLeaseAndPreservesTheOriginalHistory() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        long bytes = wiring.ledger().read().historyBytes();
        jdbc.execute("CREATE TRIGGER fixture_refuse_checkpoint BEFORE INSERT ON ai_context_checkpoint "
                + "BEGIN SELECT RAISE(ABORT, 'fixture checkpoint write failure'); END");

        assertThrows(IllegalStateException.class, () -> prepare(plan, current, plan.messages(), 8000, this::summary));
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM ai_context_compaction_lease WHERE session_id = ?", String.class, session));
        assertEquals(bytes, wiring.ledger().read().historyBytes());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED'", Integer.class));
    }

    @Test void stopDuringSummaryPreservesSourcesAndReleasesTheLease() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, (t, p) -> {
            current.handle().requestCancel(); return summary(t, p);
        }));
        assertEquals("CONTEXT_COMPACTION_STOPPED", error.code());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM ai_context_compaction_lease WHERE session_id = ?", String.class, session));
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED'", Integer.class));
    }

    @Test void sourceDeletionDuringGenerationPreventsPublication() throws Exception {
        var turns = history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, (t, p) -> {
            try { wiring.turns().clearContent(turns.get(0).requestId(), "2026-11-15T00:00:00Z"); }
            catch (Exception e) { throw new RuntimeException(e); }
            return summary(t, p);
        }));
        assertEquals("CONTEXT_CHANGED", error.code());
        assertTrue(managed.checkpoints().read(session).isEmpty());
    }

    @Test void capacityRejectionDoesNotLeaveAStoredSummaryOrSucceededLease() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var storage = wiring.ledger().read();
        assertEquals(com.drillnotebook.app.repository.ChatStorageLedger.LimitResult.APPLIED,
                wiring.ledger().updateLimits(storage.revision(), storage.usedHistory(), storage.attachmentLimitBytes()));
        assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, this::summary));
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(storage.historyBytes(), wiring.ledger().read().historyBytes());
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM ai_context_compaction_lease WHERE session_id = ?", String.class, session));
    }

    @Test void checkpointSurvivesFinalizationAndRestartAtANewerCutoff() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var view = prepare(plan(current, 8000), current, plan(current, 8000).messages(), 8000, this::summary);
        finish(current, "完成这一轮");
        var next = begin("下一步");
        managed.stack().indexes().shutdown();
        managed = ChatContextWiring.wireManaged(source, jdbc, wiring);
        assertTrue(managed.coordinator().load(session, 1, managed.stack().policy().current().revision(), next.turnId(), PASSWORD).isPresent());
        var nextPlan = plan(next, 8000);
        var continued = prepare(nextPlan, next, nextPlan.messages(), 8000, this::summary);
        assertTrue(continued.prepared().serialized().contains("会话续接摘要"));
        assertTrue(continued.prepared().serialized().contains("完成这一轮"));
        assertEquals(view.checkpoint().row().revision(), continued.checkpoint().row().revision());
        assertTrue(managed.coordinator().load(session, 1, nextPlan.policyRevision(), view.checkpoint().sources().stream().filter(s -> s.kind().equals("TURN")).findFirst().orElseThrow().turnId(), PASSWORD).isEmpty());
    }

    @Test void invalidatingAnAlreadyAppliedSummaryCannotSendOnlyTheShortenedRemainder() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var original = plan(current, 8000);
        var view = prepare(original, current, original.messages(), 8000, this::summary);
        var applied = original.withPreparedView(view.messages(), view.prepared().context(), 6688, view.checkpoint().row().revision(), view.checkpoint().sources(), view.notices());
        wiring.immediate().execute(c -> { com.drillnotebook.app.repository.ChatContextCheckpointRepository.invalidateIn(c, session); return null; });
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(applied, current, view.messages(), 8000, this::summary));
        assertEquals("CONTEXT_CHANGED", error.code());
        var restored = managed.stack().contexts().restoreAfterMemoryForget(applied.withContinuationRevision(null), current,
                view.messages(), 32000, fingerprint(), target.apiFormat(), target.model(), target.maxTokens());
        assertEquals(8, restored.messages().stream().map(ContextSource::from).filter(s -> s != null && s.kind().equals("TURN")).map(ContextSource::key).distinct().count());
        assertFalse(restored.messages().stream().anyMatch(m -> m.containsKey(ContextCoordinator.CHECKPOINT_TAG)));
    }

    @Test void forgetAndRevokeReleaseCheckpointCapacityAtomically() throws Exception {
        var vault = managed.stack().vault();
        assertEquals(201, vault.create("password", PASSWORD, "create-vault").status());
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var view = prepare(plan, current, plan.messages(), 8000, this::summary);
        long withSummary = wiring.ledger().read().historyBytes();
        long summaryBytes = view.checkpoint().row().bytes();
        wiring.memory().bumpEpoch(wiring.memory().readEnvelope().orElseThrow().vaultId());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(withSummary - summaryBytes, wiring.ledger().read().historyBytes());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_context_compaction_lease", Integer.class));
        var refreshed = plan(current, 8000);
        var again = prepare(refreshed, current, refreshed.messages(), 8000, this::summary);
        long beforeRevoke = wiring.ledger().read().historyBytes();
        managed.stack().policy().revokeConsent("MEMORY_USE");
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(beforeRevoke - again.checkpoint().row().bytes(), wiring.ledger().read().historyBytes());
    }

    @Test void closedToolEvidenceAndSignedStateReplayAcrossRoundsWithoutOrdinaryThinking() throws Exception {
        var old = begin("读取资料");
        var messages = stepMessages("c1", "read_material", "{\"status\":\"OK\",\"text\":\"跨轮工具证据\"}");
        messages.get(0).put("reasoning_content", "普通思考不应重发");
        messages.get(0).put("anthropic_thinking", List.of(Map.of("type", "thinking", "thinking", "signed reasoning", "signature", "fixture-signature")));
        var saved = managed.entries().save(old, target, 1, "TOOL_STEP", messages, true, null);
        finish(old, "读完了");
        var current = begin("依据刚读到的资料");
        var plan = plan(current, 32000);
        var prepared = upstream.prepare(target, plan.messages(), List.of(), false, 32000, "CHAT");
        assertTrue(prepared.serialized().contains("跨轮工具证据"));
        assertTrue(prepared.serialized().contains("tool_call_id"));
        assertFalse(prepared.serialized().contains("普通思考不应重发"));
        assertFalse(prepared.serialized().contains("_context"));
        assertTrue(saved.forTarget(fingerprint(), target.apiFormat(), target.model()).get(0).containsKey("anthropic_thinking"));
        assertFalse(saved.forTarget(fingerprint(), "anthropic_messages", "different-model").get(0).containsKey("anthropic_thinking"));
        assertFalse(managed.entries().read(old.turnId(), PASSWORD).get(0).messages().get(0).containsKey("reasoning_content"));
    }

    @Test void interruptedReplySegmentsAndToolStepsInterleaveWithoutDuplicatingCanonicalReply() throws Exception {
        var old = begin("原提问");
        var prefix = Map.<String,Object>of("role", "assistant", "content", "前段");
        managed.entries().save(old, target, 1, "RECOVERY", List.of(prefix, Map.of("role", "user", "content", "应用续接说明")), true, null, 0, 2);
        managed.entries().save(old, target, 1, "TOOL_STEP", stepMessages("c2", "read_material", "{\"status\":\"OK\"}"), true, null, 2, 0);
        finish(old, "前段后段");
        var current = begin("继续");
        String payload = upstream.prepare(target, plan(current, 32000).messages(), List.of(), false, 32000, "CHAT").serialized();
        assertEquals(1, occurrences(payload, "前段"));
        assertEquals(1, occurrences(payload, "后段"));
        assertTrue(payload.indexOf("前段") < payload.indexOf("c2"));
        assertTrue(payload.indexOf("c2") < payload.indexOf("后段"));
    }

    @Test void malformedOrOpenToolGroupsCannotBecomeClosedEvidence() throws Exception {
        var current = begin("读取");
        var complete = stepMessages("c1", "read_material", "{\"status\":\"OK\"}");
        var open = managed.entries().save(current, target, 1, "TOOL_STEP", complete.subList(0, 1), false, null);
        assertTrue(managed.entries().read(current.turnId(), PASSWORD).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> managed.entries().save(current, target, 1, "TOOL_STEP", complete.subList(0, 1), true, open));
        assertThrows(IllegalArgumentException.class, () -> ChatContextEntryService.validateClosed(List.of(complete.get(complete.size() - 1))));
        assertThrows(IllegalArgumentException.class, () -> ChatContextEntryService.validateClosed(List.of(complete.get(0), Map.of("role", "user", "content", "插入"), complete.get(complete.size() - 1))));
        var closed = managed.entries().save(current, target, 1, "TOOL_STEP", complete, true, open);
        assertEquals(2, closed.entry().revision());
        assertThrows(Exception.class, () -> managed.entries().save(current, target, 1, "TOOL_STEP", complete, true, closed));
    }

    @Test void longSingleTurnCompactionKeepsCurrentInputAndConfirmedWriteReceipts() throws Exception {
        var current = begin("先保存结论，再整理未完成工作");
        var plan = plan(current, 8000);
        var raw = new ArrayList<>(plan.messages());
        for (int i = 1; i <= 9; i++) raw.addAll(managed.entries().save(current, target, 1, "TOOL_STEP",
                stepMessages("write" + i, "create_material", jsonString(Map.of("status", "CREATED", "detail", "长工具资料".repeat(320)))), true, null).forCurrent());
        var view = prepare(plan, current, raw, 8000, this::summary);
        assertTrue(view.compressed());
        assertTrue(view.checkpoint().sources().stream().anyMatch(s -> s.kind().equals("STEP")));
        assertFalse(view.checkpoint().summary().receipts().isEmpty());
        assertTrue(view.prepared().serialized().contains("先保存结论，再整理未完成工作"));
        assertTrue(view.prepared().serialized().contains("write9"));
        assertTrue(view.prepared().serialized().contains("write8") || view.checkpoint().summary().receipts().contains("CALL:" + current.requestId() + ":write8:CREATED"));
        ChatContextEntryService.validateClosed(view.messages().stream().filter(m -> !"system".equals(m.get("role"))).toList());
    }

    @Test void memoryWithdrawalPreservesPairingAndReceiptsWhileRemovingPrivateContents() throws Exception {
        var messages = stepMessages("c1", "create_memory", "{\"status\":\"CREATED\",\"value\":\"private memory value\"}");
        var function = Map.of("name", "create_memory", "arguments", "{\"value\":\"private memory value\"}");
        messages.get(0).put("tool_calls", List.of(Map.of("id", "c1", "type", "function", "function", function)));
        var safe = ChatContextEntryService.withoutMemoryContents(messages);
        ChatContextEntryService.validateClosed(safe);
        assertFalse(jsonString(safe).contains("private memory value"));
        assertTrue(jsonString(safe).contains("CREATED"));
        assertFalse(ChatContextEntryService.usesMemory(safe));
        assertFalse(upstream.prepare(target, safe, List.of(), false, 8000, "CHAT").serialized().contains("_context"));
    }

    @Test void forgedSummarySourcesOrMissingConfirmedReceiptsAreRejected() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, (t, p) ->
                new AiUpstreamClient.Result("{\"goal\":\"继续\",\"constraints\":[],\"decisions\":[],\"completed\":[],\"pending\":[],\"unknown\":[],\"sources\":[\"TURN:9999\"],\"receipts\":[]}", "", "stop")));
        assertEquals("CONTEXT_SUMMARY_INVALID", error.code());
        assertTrue(managed.checkpoints().read(session).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"length", "max_tokens"})
    void aTruncatedSummaryCannotReplaceOriginalHistory(String finishReason) throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, (t, p) -> {
            var complete = summary(t, p);
            return new AiUpstreamClient.Result(complete.text(), "", finishReason);
        }));
        assertEquals("CONTEXT_SUMMARY_INCOMPLETE", error.code());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE status='COMPLETED'", Integer.class));
    }

    @Test void ineffectiveSummariesStopAfterOneAttemptWithoutPublishing() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var attempts = new AtomicInteger();
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, plan.messages(), 8000, (t, p) -> {
            attempts.incrementAndGet();
            try {
                var value = json.readTree(summary(t, p).text()).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("goal", "长".repeat(3000));
                ((com.fasterxml.jackson.databind.node.ObjectNode) value).set("constraints", json.valueToTree(List.of("约束".repeat(1200), "其他".repeat(1200))));
                return new AiUpstreamClient.Result(jsonString(value), "", "stop");
            } catch (Exception e) { throw new RuntimeException(e); }
        }));
        assertEquals("CONTEXT_COMPACTION_INEFFECTIVE", error.code());
        assertEquals(1, attempts.get());
        assertTrue(managed.checkpoints().read(session).isEmpty());
    }

    @Test void failedSoftThresholdCompactionFallsBackToTheCompleteViewWhenItStillFits() throws Exception {
        history(4, 300);
        var current = begin("继续");
        var plan = plan(current, 32000);
        int input = upstream.prepare(target, plan.messages(), List.of(), false, 32000, "CHAT").context().estimatedInputTokens();
        int window = (input * 4 + 2) / 3;
        var settings = new AiContextSettings(true, 65);
        var budget = ContextWindowBudget.of(window, target.maxTokens(), settings);
        assertTrue(input > budget.softInput() && input <= budget.hardInput());
        var view = managed.coordinator().prepare(plan, current, plan.messages(), target, List.of(), false, window, settings,
                upstream, (t, p) -> { throw new IllegalStateException("fixture summary unavailable"); }, () -> managed.stack().contexts().matches(plan));
        assertFalse(view.compressed());
        assertTrue(view.notices().contains("CONTEXT_COMPACTION_FAILED"));
        assertEquals(input, view.prepared().context().estimatedInputTokens());
        assertTrue(view.prepared().serialized().contains("历史问题1"));
        assertTrue(view.prepared().serialized().contains("历史回答4"));
        assertTrue(managed.checkpoints().read(session).isEmpty());
    }

    @Test void summariesMustKeepEveryConfirmedWriteReceipt() throws Exception {
        var current = begin("继续已经保存的工作");
        var plan = plan(current, 8000);
        var raw = new ArrayList<>(plan.messages());
        for (int i = 1; i <= 7; i++) raw.addAll(managed.entries().save(current, target, 1, "TOOL_STEP",
                stepMessages("save" + i, "create_material", jsonString(Map.of("status", "CREATED", "text", "资料".repeat(700)))), true, null).forCurrent());
        var error = assertThrows(ChatTurnRejectException.class, () -> prepare(plan, current, raw, 8000, (t, p) -> {
            try {
                var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(summary(t, p).text());
                assertTrue(value.path("receipts").size() > 0);
                value.set("receipts", json.createArrayNode());
                return new AiUpstreamClient.Result(jsonString(value), "", "stop");
            } catch (Exception e) { throw new RuntimeException(e); }
        }));
        assertEquals("CONTEXT_SUMMARY_INVALID", error.code());
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(7, managed.entries().read(current.turnId(), PASSWORD).size());
    }

    @Test void aContextResetReleasesCheckpointCapacityAndRestartsWithoutOldSources() throws Exception {
        history(8, 500);
        var current = begin("继续");
        var plan = plan(current, 8000);
        var view = prepare(plan, current, plan.messages(), 8000, this::summary);
        finish(current, "已整理");
        long before = wiring.ledger().read().historyBytes();
        assertEquals(2, wiring.metadata().resetContextGeneration(session));
        assertTrue(managed.checkpoints().read(session).isEmpty());
        assertEquals(before - view.checkpoint().row().bytes(), wiring.ledger().read().historyBytes());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_context_compaction_lease", Integer.class));
        var next = begin("重新开始");
        var nextPlan = plan(next, 32000);
        assertFalse(nextPlan.messages().toString().contains("历史问题"));
        assertEquals(2, nextPlan.contextGeneration());
    }

    private List<ChatTurnService.Lease> history(int count, int repeat) {
        var turns = new ArrayList<ChatTurnService.Lease>();
        for (int i = 1; i <= count; i++) turns.add(complete("历史问题" + i + "资料".repeat(repeat), "历史回答" + i + "结论".repeat(repeat)));
        return turns;
    }
    private ChatTurnService.Lease complete(String question, String answer) { var turn = begin(question); finish(turn, answer); return turn; }
    private ChatTurnService.Lease begin(String question) {
        return wiring.turnService().begin(new ChatTurnService.BeginInput(UuidV7.generate(System.currentTimeMillis() + ++requestSequence),
                2, session, question, PASSWORD, List.of(), Map.of())).lease();
    }
    private void finish(ChatTurnService.Lease turn, String answer) { wiring.turnService().finalizeTurn(turn, ChatTurnService.UpstreamReply.of(answer, null, "stop"), null); }
    private String fingerprint() { return UsageRepository.targetFingerprint(target.baseUrl(), target.apiFormat(), target.model()); }
    private ChatContextPlan plan(ChatTurnService.Lease turn, int window) throws Exception {
        return managed.stack().contexts().plan(turn, wiring.cipher().read(jdbc.queryForObject("SELECT content_cipher FROM ai_chat_message WHERE id = ?", String.class, turn.userMessageId()),
                jdbc.queryForObject("SELECT content_meta FROM ai_chat_message WHERE id = ?", String.class, turn.userMessageId()), PASSWORD).value(), List.of(), Map.of(), Map.of("use", false),
                window, true, target.maxTokens(), fingerprint(), target.apiFormat(), target.model());
    }
    private ContextCoordinator.View prepare(ChatContextPlan plan, ChatTurnService.Lease turn, List<Map<String,Object>> raw, int window, ContextCoordinator.SummaryCaller caller) {
        return managed.coordinator().prepare(plan, turn, raw, target, List.of(), false, window, AiContextSettings.DEFAULTS, upstream, caller, () -> managed.stack().contexts().matches(plan));
    }
    private AiUpstreamClient.Result summary(AiUpstreamClient.Upstream t, com.drillnotebook.app.model.PreparedRequest request) {
        JsonNode messages = json.valueToTree(request.body()).path("messages");
        try {
            var data = json.readTree(text(messages.get(messages.size() - 1).path("content")));
            var out = new LinkedHashMap<String,Object>();
            out.put("goal", "继续当前任务"); out.put("constraints", List.of("用户最后要求：先不要归档，只整理计划"));
            out.put("decisions", List.of("资料只作参考")); out.put("completed", List.of("已确认写入以回执为准"));
            out.put("pending", List.of("继续未完成工作")); out.put("unknown", List.of());
            out.put("sources", json.convertValue(data.path("availableSources"), List.class)); out.put("receipts", json.convertValue(data.path("requiredReceipts"), List.class));
            return new AiUpstreamClient.Result(jsonString(out), "", "stop");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static ArrayList<Map<String,Object>> stepMessages(String id, String tool, String result) {
        return new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", id, "type", "function", "function", Map.of("name", tool, "arguments", "{}"))))),
                new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", id, "content", result))));
    }
    private String jsonString(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new RuntimeException(e); } }
    private static String text(Object value) {
        if (value instanceof JsonNode node) { if (node.isTextual()) return node.asText(); var s = new StringBuilder(); if (node.isArray()) node.forEach(b -> s.append(b.path("text").asText())); return s.toString(); }
        if (value instanceof List<?> blocks) return blocks.stream().map(b -> b instanceof Map<?,?> m ? String.valueOf(m.get("text")) : "").collect(java.util.stream.Collectors.joining());
        return String.valueOf(value);
    }
    private static int occurrences(String text, String part) { return text.split(java.util.regex.Pattern.quote(part), -1).length - 1; }
}
