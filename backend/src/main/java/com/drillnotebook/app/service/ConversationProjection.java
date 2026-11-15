package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.AttachmentStub;
import com.drillnotebook.app.repository.ChatHistoryRepository.LegacyMessage;
import com.drillnotebook.app.repository.ChatHistoryRepository.TurnHistory;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 唯一的历史投影（执行方案 §9.4、contracts §4.5）：固定顺序
 * 读会话版本 → 取有效摘要检查点 → 倒序有界扫描 → 过滤非法来源 → 交给组装器。
 *
 * <p>四条硬规则：</p>
 * <ul>
 *   <li>只排除被<b>有效 coverage</b> 替代的原轮次，绝不按 {@code through_turn_id} 一刀切；</li>
 *   <li>一批最多 16 轮，分页读取有时间、字节与工作量保护；没有有效摘要时保留连续的合格历史；</li>
 *   <li>本轮正在跑的 RUNNING 轮次不算自己的历史——当前用户输入由请求单独放最后；</li>
 *   <li>coverage 也不能把最近 2 个完整轮次整段替掉（§9.2 近期区目标）。</li>
 * </ul>
 *
 * <p>展示资格与注入资格是两回事：这里只产出注入候选，历史展示分页走
 * {@code ChatHistoryRepository#page}，不经过本类。</p>
 */
@Service
public class ConversationProjection {
    /** 一批最多 16 轮（§7.2.2 尾段）。 */
    public static final int BATCH_TURNS = 16;
    /** Resource guard, not a model retention policy. Hitting it produces an explicit gap error. */
    public static final int MAX_SCANNED_TURNS = 16384;
    private static final long MAX_SCAN_BYTES = 32L * 1024 * 1024;
    private static final long MAX_SCAN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 摘要加密 payload 明文上限（§2.5）：超过就不信任这份检查点。 */
    static final int MAX_SUMMARY_PAYLOAD_BYTES = 32 * 1024;
    /** coverage 段数上限（§2.5）：超了旧检查点失效，从当前窗口重建并标缺口。 */
    static final int MAX_COVERAGE_RANGES = 128;
    /** 最近这么多条完整轮次永远按原文保留，摘要替不掉。 */
    static final int MIN_RECENT_TURNS = 2;

    private static final Logger log = LoggerFactory.getLogger(ConversationProjection.class);
    /** 检查点存在却用不了时的统一提示：裁剪不能被说成"已经总结过"（T03）。 */
    public static final String NOTICE_SUMMARY_UNAVAILABLE = "SUMMARY_UNAVAILABLE";

    /** 一次投影所需的最小上下文：本轮 turnId 之前的历史才可能是候选。 */
    public record Request(long sessionId, long currentTurnId, long contextGeneration, String masterPassword,
                          int recentTokenBudget, boolean memoriesAllowed) {
        public Request(long sessionId, long currentTurnId, long contextGeneration, String masterPassword, int recentTokenBudget) {
            this(sessionId, currentTurnId, contextGeneration, masterPassword, recentTokenBudget, true);
        }
    }

    /** 一条候选：正文已解密，附件只带身份（要不要取密文由组装器按图片策略决定）。 */
    public record Item(long turnId, String requestId, boolean legacy, Long userMessageId, Long assistantMessageId,
                       String userText, String assistantText, List<AttachmentStub> attachments, String startedAt,
                       int estimatedTokens, List<ChatSupplement> supplements,
                       List<ChatContextEntryService.Step> steps, long sourceRevision) {
        public Item(long turnId, String requestId, boolean legacy, Long userMessageId, Long assistantMessageId,
                    String userText, String assistantText, List<AttachmentStub> attachments, String startedAt,
                    int estimatedTokens, List<ChatSupplement> supplements) {
            this(turnId, requestId, legacy, userMessageId, assistantMessageId, userText, assistantText,
                    attachments, startedAt, estimatedTokens, supplements, List.of(), 1);
        }
        public Item(long turnId,String requestId,boolean legacy,Long userMessageId,Long assistantMessageId,
                    String userText,String assistantText,List<AttachmentStub> attachments,String startedAt,int estimatedTokens){
            this(turnId,requestId,legacy,userMessageId,assistantMessageId,userText,assistantText,attachments,startedAt,estimatedTokens,List.of());
        }
    }

    /**
     * 检查点的两用视图：{@code audit} 是按契约形状落进审计的那份（不含正文），
     * {@code modelSummary} 只在这一轮交给组装器，绝不落进 result_meta。
     */
    public record SummaryView(ContextAudit.Summary audit, String modelSummary, int estimatedTokens) {
    }

    /** 投影结果：候选按时间升序，外加扫描范围、有效摘要、中断态与会话版本。 */
    public record Projection(List<Item> candidates, ContextAudit.Scan scan, SummaryView summary,
                             ContextAudit.ResumeState resumeState, List<String> notices, long historyRevision,
                             List<Item> coveredCandidates, Long observedSummaryRevision, Long observedContinuationRevision) {
        public Projection(List<Item> candidates, ContextAudit.Scan scan, SummaryView summary,
                          ContextAudit.ResumeState resumeState, List<String> notices, long historyRevision,
                          List<Item> coveredCandidates, Long observedSummaryRevision) {
            this(candidates, scan, summary, resumeState, notices, historyRevision, coveredCandidates, observedSummaryRevision, null);
        }
    }

    /** 扫描中间态：候选 + 各类排除的计数，最后统一进审计。 */
    private static final class Scan {
        private final List<Item> newestFirst = new ArrayList<>();
        /** 被摘要 coverage 排除的原文（新→旧）：摘要若被预算舍弃，按它做有界回补（§9.4）。 */
        private final List<Item> coveredFirst = new ArrayList<>();
        private int wrongGeneration;
        private int tombstoned;
        private int unfinished;
        private int legacyIncomplete;
        private int unreadable;
        private int covered;
        private int completeSeen;
        private int rowsRead;
        private Long oldestScannedTurn;
        private long bytes;
        private final long deadline = System.nanoTime() + MAX_SCAN_NANOS;
        private boolean legacyExhausted;
    }

    private final ChatHistoryRepository history;
    private final SessionContextRepository checkpoints;
    private final ChatSessionMetadataRepository sessions;
    private final ChatMessageCipher cipher;
    private final MemoryRepository memory;
    private final MemoryCryptoService crypto;
    private final MemoryVaultService vault;
    private final MemoryPolicyService policy;
    private final TokenEstimator estimator;
    private final ObjectMapper mapper;
    private ChatContextEntryService entries;
    private ContextCoordinator coordinator;

    @org.springframework.beans.factory.annotation.Autowired
    public void setContextEntries(ChatContextEntryService entries) { this.entries = entries; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setContextCoordinator(ContextCoordinator coordinator) { this.coordinator = coordinator; }

    public ConversationProjection(ChatHistoryRepository history, SessionContextRepository checkpoints,
                                  ChatSessionMetadataRepository sessions, ChatMessageCipher cipher,
                                  MemoryRepository memory, MemoryCryptoService crypto, MemoryVaultService vault,
                                  MemoryPolicyService policy, TokenEstimator estimator, ObjectMapper mapper) {
        this.history = history;
        this.checkpoints = checkpoints;
        this.sessions = sessions;
        this.cipher = cipher;
        this.memory = memory;
        this.crypto = crypto;
        this.vault = vault;
        this.policy = policy;
        this.estimator = estimator;
        this.mapper = mapper;
    }

    /**
     * 构建候选。审计草稿由调用方传入：投影阶段的每一次排除都要落进同一份账本，
     * 而不是另建一套"为什么少了"的说法。
     */
    public Projection project(Request request, ContextAudit.Builder audit) throws SQLException {
        List<String> notices = new ArrayList<>();
        long historyRevision = sessions.read(request.sessionId())
                .map(ChatSessionMetadataRepository.State::historyRevision).orElse(0L);
        Long continuationRevision = coordinator == null ? null : coordinator.revision(request.sessionId());
        var continuation = coordinator == null ? null : coordinator.load(request.sessionId(), request.contextGeneration(),
                policy.current().revision(), request.currentTurnId(), request.masterPassword(), request.memoriesAllowed()).orElse(null);
        // 观测到的检查点版本与实际采用的摘要分开记：无效/锁定摘要降级成"没有摘要"时，
        // 发送前复核仍要能发现检查点真的被替换过（§4.5）
        Optional<SessionContextRepository.Checkpoint> checkpoint = checkpoints.read(request.sessionId());
        Long observedSummaryRevision = checkpoint.map(SessionContextRepository.Checkpoint::revision).orElse(null);
        // Legacy vault checkpoints remain readable through their old API; the new chat-domain view
        // is rebuilt from real sources without extending vault unlock/consent to migrate a summary.
        SummaryView summaryView = entries == null ? readSummary(request, checkpoint, audit, notices) : null;
        ContextAudit.Summary covered = summaryView == null ? null : summaryView.audit();

        Scan scan = scanTurns(request, covered, continuation);
        if (request.contextGeneration() == 1) {
            appendLegacy(request, scan, audit, continuation);
        }
        List<Item> candidates = withAttachmentIdentities(scan.newestFirst);
        Collections.reverse(candidates);
        List<Item> coveredCandidates = withAttachmentIdentities(scan.coveredFirst);
        Collections.reverse(coveredCandidates);

        if (scan.wrongGeneration > 0) {
            audit.omit(ContextAudit.Omission.WRONG_GENERATION, "TURN", scan.wrongGeneration);
        }
        if (scan.tombstoned > 0) {
            audit.omit(ContextAudit.Omission.SOURCE_DELETED, "TURN", scan.tombstoned);
        }
        if (scan.unfinished > 0) {
            audit.omit(ContextAudit.Omission.NON_COMPLETED, "TURN", scan.unfinished);
        }
        if (scan.legacyIncomplete > 0) {
            audit.omit(ContextAudit.Omission.NON_COMPLETED, "LEGACY", scan.legacyIncomplete);
        }
        if (scan.unreadable > 0) audit.omit(ContextAudit.Omission.SOURCE_CHANGED, "TURN", scan.unreadable);
        if (scan.covered > 0) audit.omit(ContextAudit.Omission.COVERED_BY_SUMMARY, "TURN", scan.covered);
        reportWindowEdges(request, scan, covered, audit);

        Long firstTurnId = null;
        Long lastTurnId = null;
        for (Item item : candidates) {
            if (item.legacy()) continue;
            if (firstTurnId == null || item.turnId() < firstTurnId) firstTurnId = item.turnId();
            if (lastTurnId == null || item.turnId() > lastTurnId) lastTurnId = item.turnId();
        }
        boolean hasOlder = hasOlder(request, scan);
        ContextAudit.Scan window = new ContextAudit.Scan(firstTurnId, lastTurnId, scan.rowsRead, hasOlder);
        return new Projection(List.copyOf(candidates), window, summaryView, resumeState(request),
                List.copyOf(notices), historyRevision, List.copyOf(coveredCandidates), observedSummaryRevision, continuationRevision);
    }

    /** 倒序分页扫描：有效 coverage 不解密；工作量保护触发时明确拒绝有缺口的发送。 */
    private Scan scanTurns(Request request, ContextAudit.Summary summary, ContextCoordinator.Loaded continuation) throws SQLException {
        Scan scan = new Scan();
        long cursor = request.currentTurnId();
        int remaining = MAX_SCANNED_TURNS;
        while (remaining > 0) {
            int limit = Math.min(BATCH_TURNS, remaining);
            List<TurnHistory> batch = history.turnBatch(request.sessionId(), cursor, limit);
            if (batch.isEmpty()) break;
            remaining -= batch.size();
            scan.rowsRead += batch.size();
            cursor = batch.get(batch.size() - 1).turnId();
            scan.oldestScannedTurn = cursor;
            for (int index = 0; index < batch.size(); index++) {
                TurnHistory row = batch.get(index);
                scan.bytes += com.drillnotebook.app.repository.ChatStorageSizer.storedBytes(row.userContent(), row.userContentCipher(), row.userContentMeta(),
                        row.assistantContent(), row.assistantContentCipher(), row.assistantContentMeta());
                if (scan.bytes > MAX_SCAN_BYTES || System.nanoTime() > scan.deadline)
                    throw scanLimit();
                if (row.contextGeneration() != request.contextGeneration()) {
                    // 切过作用域的旧正文永远不再进新代次的上下文（§2.2）
                    scan.wrongGeneration++;
                    continue;
                }
                if (row.contentDeletedAt() != null) {
                    // 原话已被用户清理：展示还有墓碑，但没有可注入的正文
                    scan.tombstoned++;
                    continue;
                }
                if (!ChatTurnRepository.STATUS_COMPLETED.equals(row.status())) {
                    // 只有 COMPLETED 才是合法上下文来源；RUNNING 是本轮自己
                    scan.unfinished++;
                    continue;
                }
                if (continuation != null && continuation.coversTurn(row.turnId())) { scan.covered++; continue; }
                // 先解密再判去留：坏行要如实计入 SOURCE_CHANGED，不能因为"反正被摘要替了"就当它不存在
                String userText;
                String assistantText;
                try {
                    userText = read(row.userContent(), row.userContentCipher(), row.userContentMeta(), request);
                    assistantText = read(row.assistantContent(), row.assistantContentCipher(),
                            row.assistantContentMeta(), request);
                } catch (ChatCryptoException error) {
                    scan.unreadable++;                    continue;
                }
                if (userText == null || userText.isBlank() || assistantText == null || assistantText.isBlank()) {
                    scan.unfinished++;
                    continue;
                }
                Item item;
                try { item=itemOf(row,userText,assistantText,request, continuation); }
                catch(ChatCryptoException error){scan.unreadable++;continue;}
                scan.bytes += item.steps().stream().mapToLong(s -> s.entry().bytes()).sum()
                        + item.supplements().stream().mapToLong(s -> s.contextText().getBytes(StandardCharsets.UTF_8).length).sum();
                if (scan.bytes > MAX_SCAN_BYTES || System.nanoTime() > scan.deadline) throw scanLimit();
                scan.completeSeen++;
                // 最近两条完整轮次永远留原文：摘要替掉它们会让近期区凭空消失（T02）
                if (isCovered(summary, row.turnId()) && scan.completeSeen > MIN_RECENT_TURNS) {
                    // 先留一份原文：摘要被预算舍弃时 coverage 不再成立，要用它做有界回补（§9.4）
                    scan.coveredFirst.add(item);
                    scan.covered++;
                    continue;
                }
                scan.newestFirst.add(item);
            }
            if (batch.size() < limit) break;
        }
        if (remaining == 0 && history.hasOlderTurn(request.sessionId(), cursor)) throw scanLimit();
        return scan;
    }

    private Item itemOf(TurnHistory row, String userText, String assistantText,Request request, ContextCoordinator.Loaded continuation) throws SQLException {
        var supplements=history.supplements(row.requestId(),cipher,request.masterPassword());
        var steps = entries == null ? List.<ChatContextEntryService.Step>of() : entries.read(row.turnId(), request.masterPassword(),
                continuation == null ? java.util.Set.of() : continuation.coveredEntries());
        return new Item(row.turnId(), row.requestId(), false, row.userMessageId(),
                row.assistantMessageId(), userText, assistantText, List.of(), row.startedAt(),
                estimator.estimateText(userText) + estimator.estimateText(assistantText)
                        +supplements.stream().mapToInt(s->estimator.estimateText(s.contextText())).sum()
                        +steps.stream().mapToInt(s->s.entry().estimatedTokens()).sum(), supplements, steps, row.sourceRevision());
    }

    /** Legacy pairs are paged backwards with the same work guards and precise encrypted-source identities. */
    private void appendLegacy(Request request, Scan scan, ContextAudit.Builder audit, ContextCoordinator.Loaded continuation) throws SQLException {
        long cursor = history.messageCutoffOfTurn(request.sessionId(), request.currentTurnId());
        LegacyMessage reply = null;
        int inspected = 0;
        while (inspected < MAX_SCANNED_TURNS * 2 - scan.rowsRead * 2) {
            List<LegacyMessage> rows = history.legacyBatch(request.sessionId(), cursor, BATCH_TURNS * 2);
            if (rows.isEmpty()) { scan.legacyExhausted = true; break; }
            inspected += rows.size();
            cursor = rows.get(rows.size() - 1).messageId();
            for (LegacyMessage message : rows) {
                scan.bytes += com.drillnotebook.app.repository.ChatStorageSizer.storedBytes(message.content(), message.contentCipher(), message.contentMeta());
                if (scan.bytes > MAX_SCAN_BYTES || System.nanoTime() > scan.deadline) throw scanLimit();
                if ("assistant".equals(message.role())) {
                    if (reply != null) scan.legacyIncomplete++;
                    reply = message;
                    continue;
                }
                if (!"user".equals(message.role()) || reply == null) { scan.legacyIncomplete++; reply = null; continue; }
                var pairReply = reply;
                reply = null;
                var source = ChatHistoryRepository.legacySource(message, pairReply);
                if (continuation != null && continuation.keys().contains(source.key())) { scan.covered++; continue; }
                String userText, assistantText;
                try { userText = readLegacy(message, request); assistantText = readLegacy(pairReply, request); }
                catch (ChatCryptoException error) { scan.unreadable++; continue; }
                if (userText == null || userText.isBlank() || assistantText == null || assistantText.isBlank()) { scan.legacyIncomplete++; continue; }
                scan.newestFirst.add(new Item(0, source.fingerprint(), true, message.messageId(), pairReply.messageId(), userText, assistantText,
                        List.of(), pairReply.createdAt(), estimator.estimateText(userText) + estimator.estimateText(assistantText)));
            }
            if (rows.size() < BATCH_TURNS * 2) { scan.legacyExhausted = true; break; }
        }
        if (!scan.legacyExhausted && !history.legacyBatch(request.sessionId(), cursor, 1).isEmpty()) throw scanLimit();
        scan.legacyExhausted = true;
        if (reply != null) scan.legacyIncomplete++;
        scan.rowsRead += inspected;
    }

    private List<Item> withAttachmentIdentities(List<Item> newestFirst) {
        List<Long> turnIds = new ArrayList<>();
        for (Item item : newestFirst) {
            if (!item.legacy()) turnIds.add(item.turnId());
        }
        if (turnIds.isEmpty()) return new ArrayList<>(newestFirst);
        Map<Long, List<AttachmentStub>> byTurn = new LinkedHashMap<>();
        try {
            for (AttachmentStub stub : history.attachmentStubs(turnIds)) {
                byTurn.computeIfAbsent(stub.turnId(), key -> new ArrayList<>()).add(stub);
            }
        } catch (SQLException error) {
            log.warn("附件身份读取失败，本轮按无历史附件处理: {}", error.toString());
            return new ArrayList<>(newestFirst);
        }
        List<Item> enriched = new ArrayList<>();
        for (Item item : newestFirst) {
            List<AttachmentStub> stubs = byTurn.getOrDefault(item.turnId(), List.of());
            enriched.add(stubs.isEmpty() ? item
                    : new Item(item.turnId(), item.requestId(), item.legacy(), item.userMessageId(),
                            item.assistantMessageId(), item.userText(), item.assistantText(), List.copyOf(stubs),
                            item.startedAt(), item.estimatedTokens(), item.supplements(), item.steps(), item.sourceRevision()));
        }
        return enriched;
    }

    private boolean hasOlder(Request request, Scan scan) throws SQLException {
        long boundary = scan.oldestScannedTurn == null ? request.currentTurnId() : scan.oldestScannedTurn;
        if (boundary > 1 && history.hasOlderTurn(request.sessionId(), boundary)) return true;
        return request.contextGeneration() == 1 && !scan.legacyExhausted && history.hasLegacyMessages(request.sessionId());
    }

    /** 范围外的历史：有有效摘要时给出准确计数，没有摘要时只报"没扫"、数量留 null（§4.5）。 */
    private void reportWindowEdges(Request request, Scan scan, ContextAudit.Summary summary,
                                   ContextAudit.Builder audit) throws SQLException {
        long boundary = scan.oldestScannedTurn == null ? request.currentTurnId() : scan.oldestScannedTurn;
        if (boundary <= 1 || !history.hasOlderTurn(request.sessionId(), boundary)) return;
        if (summary == null) {
            audit.omit(ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY", null);
            return;
        }
        audit.omit(ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY",
                history.countTurnsBefore(request.sessionId(), boundary));
    }

    private String read(String plain, String cipherText, String meta, Request request) {
        ChatMessageCipher.Read read = cipher.readLegacy(plain, cipherText, meta, request.masterPassword());
        return switch (read.status()) {
            case NEEDS_PASSWORD -> throw new ChatCryptoException("读取历史消息需要主密码");
            case UNREADABLE -> throw new ChatCryptoException("历史消息无法解密");
            default -> read.value();
        };
    }

    private String readLegacy(LegacyMessage row, Request request) {
        return read(row.content(), row.contentCipher(), row.contentMeta(), request);
    }

    private static boolean enough(List<Item> candidates, int recentTokenBudget) {
        if (candidates.size() < MIN_RECENT_TURNS) return false;
        int total = 0;
        for (Item item : candidates) total += item.estimatedTokens();
        return total >= recentTokenBudget;
    }

    private static ChatTurnRejectException scanLimit() {
        return new ChatTurnRejectException(413, "CONTEXT_SCAN_LIMIT", "历史读取达到时间或空间保护上限，未丢弃未读历史；请清理旧内容或缩小会话范围", Map.of("hasUnscannedHistory", true));
    }

    /**
     * 检查点合法性（§2.5 + T04）：generation、摘要授权、库 epoch、payload 尺寸与解析、coverage 段数
     * 都过了才用。任何一条不过都只报"这份摘要不可用"——既不回退明文，也不按 through 整段排除历史。
     */
    private SummaryView readSummary(Request request, Optional<SessionContextRepository.Checkpoint> row,
                                    ContextAudit.Builder audit, List<String> notices) throws SQLException {
        return readSummary(request, row, audit, notices, false);
    }

    /** 当前窗口统计只复用检查点资格判断，不建计划、不扫描尾部或修复旧检查点。 */
    public SummaryView currentSummary(long sessionId, long contextGeneration) throws SQLException {
        return readSummary(new Request(sessionId, Long.MAX_VALUE, contextGeneration, null, 0),
                checkpoints.read(sessionId), new ContextAudit.Builder("occupancy", TokenEstimator.VERSION),
                new ArrayList<>(), true);
    }

    private SummaryView readSummary(Request request, Optional<SessionContextRepository.Checkpoint> row,
                                    ContextAudit.Builder audit, List<String> notices, boolean readOnly) throws SQLException {
        if (row.isEmpty()) return null;
        SessionContextRepository.Checkpoint checkpoint = row.get();
        if (checkpoint.schemaVersion() != 2) {
            if (!readOnly) checkpoints.delete(request.sessionId());
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (checkpoint.contextGeneration() != request.contextGeneration()) {
            return unavailable(audit, notices, ContextAudit.Omission.WRONG_GENERATION, 1);
        }
        // §2.1/§4.5：摘要能不能读密文、能不能外发，取决于 compressSession 与维护授权；
        // "库存在且解锁"只是本地保护状态，本身不是外发授权，不能拿它当通行证
        MemoryPolicyService.Eligibility eligible = policy.summaryEligibility();
        if (!eligible.allowed()) {
            return unavailable(audit, notices, summaryOmission(eligible.gate()), 1);
        }
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        if (envelope.isEmpty()) {
            return unavailable(audit, notices, ContextAudit.Omission.NOT_AUTHORIZED, 1);
        }
        if (envelope.get().epoch() != checkpoint.vaultEpoch()) {
            // 清空或重置库之后的旧检查点：不能恢复使用（§11.3、T04）
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_DELETED, 1);
        }
        Optional<MemoryCryptoService.MemoryKey> key = vault.currentKey();
        if (key.isEmpty()) {
            // 未解锁就是不能用记忆正文，绝不拿密文或占位充数（§4 不变量）
            return unavailable(audit, notices, ContextAudit.Omission.LOCKED, 1);
        }
        String payload;
        try {
            payload = crypto.decrypt(key.get(), "ai_session_context", String.valueOf(request.sessionId()),
                    checkpoint.revision(), checkpoint.payloadCipher(), checkpoint.cryptoMeta());
        } catch (MemoryCryptoService.MemoryCryptoException error) {
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_PAYLOAD_BYTES) {
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        Parsed parsed = parsePayload(payload);
        if (parsed == null) {
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (overrunsCheckedBound(parsed.ranges(), checkpoint.throughTurnId())) {
            // coverage 声称覆盖到"已检查上界"之后：越界部分没被这份摘要整理过，一旦采用，
            // 读侧会把那些轮次当"已被替代"而静默丢掉原文。整份拒收，宁可当没有摘要（§2.5）
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (parsed.ranges().size() > MAX_COVERAGE_RANGES) {
            // 段数爆表：旧检查点失效，只当"没摘要"用当前窗口重建（§2.5）
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, parsed.ranges().size());
        }
        ContextAudit.Omission stale = staleReference(request.sessionId(), parsed.references());
        if (stale != null) {
            // 引用台账里任何一条页面对不上（删了、改版了、不属于本会话的作用域），整份摘要都不再用：
            // 摘要正文里那句"见某页"已经指向别的内容，留着就是拿旧结论冒充当前事实（§8.4 第 7 条）
            return unavailable(audit, notices, stale, 1);
        }
        boolean hasGaps = !contiguous(parsed.ranges(), checkpoint.throughTurnId())
                || history.countNonCompletedThrough(request.sessionId(), checkpoint.throughTurnId()) > 0;
        ContextAudit.Summary auditSummary = new ContextAudit.Summary(checkpoint.revision(),
                checkpoint.throughTurnId(), hasGaps, List.copyOf(parsed.ranges()));
        return new SummaryView(auditSummary, parsed.modelSummary(), estimator.estimateText(parsed.modelSummary()));
    }

    /**
     * 引用台账的逐条复验（执行方案 §8.4 第 7 条）：页面还在不在、内容版本还是不是当时那一版、
     * 在不在本会话的笔记本作用域里。版本比的是 {@code note_page.content_hash} 本身，
     * 所以页面被编辑过一次就立刻失效——引用不是全文缓存，查细节仍走用户当前请求下的检索。
     *
     * @return 该报进审计的省略原因；全部对得上时为 {@code null}
     */
    private ContextAudit.Omission staleReference(long sessionId, List<Reference> references) throws SQLException {
        if (references.isEmpty()) {
            return null;
        }
        ChatSessionMetadataRepository.State state = sessions.read(sessionId).orElse(null);
        Long notebook = state == null || "GLOBAL".equals(state.memoryScopeType())
                ? null : state.memoryNotebookId();
        for (Reference reference : references) {
            Optional<String> current = history.pageContentHash(reference.pageId());
            if (current.isEmpty()) {
                return ContextAudit.Omission.SOURCE_DELETED;
            }
            if (!current.get().equals(reference.contentVersion())) {
                return ContextAudit.Omission.SOURCE_CHANGED;
            }
            if (notebook != null) {
                Optional<Long> owner = history.notebookIdOfPage(reference.pageId());
                if (owner.isEmpty() || !owner.get().equals(notebook)) {
                    return ContextAudit.Omission.SOURCE_CHANGED;
                }
            }
        }
        return null;
    }

    /** 台账里的一条引用：只需要页码与版本能判有效性，来源轮次留写侧与检查器用。 */
    private record Reference(long pageId, String contentVersion) {
    }

    /** 授权门不通过时的省略原因：锁定照实说，其余（开关关闭、无授权、目标变了）都算"没被授权"。 */
    private static ContextAudit.Omission summaryOmission(MemoryPolicyService.Gate gate) {
        return gate == MemoryPolicyService.Gate.LOCKED
                ? ContextAudit.Omission.LOCKED : ContextAudit.Omission.NOT_AUTHORIZED;
    }

    private SummaryView unavailable(ContextAudit.Builder audit, List<String> notices,
                                    ContextAudit.Omission reason, int count) {
        audit.omit(reason, "SUMMARY", count);
        notices.add(NOTICE_SUMMARY_UNAVAILABLE);
        return null;
    }

    private record Parsed(String modelSummary, List<ContextAudit.CoverageRange> ranges, List<Reference> references) {
    }

    /** 检查点 payload 的明文形状（§2.5：模型可见部分与 serverCoverage 分开；引用台账只给服务端自己看）。 */
    private Parsed parsePayload(String payload) {
        try {
            Map<String, Object> parsed = mapper.readValue(payload, new TypeReference<>() {
            });
            if (!(parsed.get("schemaVersion") instanceof Number version) || version.intValue() != 2) return null;
            List<ContextAudit.CoverageRange> ranges = new ArrayList<>();
            if (parsed.get("serverCoverage") instanceof List<?> list) {
                for (Object element : list) {
                    if (!(element instanceof Map<?, ?> map)) continue;
                    if (!(map.get("firstTurnId") instanceof Number first)
                            || !(map.get("lastTurnId") instanceof Number last)) continue;
                    if (first.longValue() <= 0 || last.longValue() < first.longValue()) continue;
                    ranges.add(new ContextAudit.CoverageRange(first.longValue(), last.longValue()));
                }
            }
            List<Reference> references = new ArrayList<>();
            if (parsed.get("references") instanceof List<?> list) {
                for (Object element : list) {
                    if (!(element instanceof Map<?, ?> map)) continue;
                    if (!(map.get("pageId") instanceof Number page) || page.longValue() <= 0) continue;
                    if (!(map.get("contentVersion") instanceof String text) || text.isBlank()) continue;
                    references.add(new Reference(page.longValue(), text));
                }
            }
            Object summary = parsed.get("modelSummary");
            return new Parsed(summary == null ? "" : String.valueOf(summary), ranges, references);
        } catch (Exception error) {
            return null;
        }
    }

    /** coverage 必须连续铺到 through，中间断了就是缺口（§2.5「不能跨被跳过的本会话轮次」）。 */
    private static boolean contiguous(List<ContextAudit.CoverageRange> ranges, long throughTurnId) {
        if (ranges.isEmpty()) return false;
        long expected = ranges.get(0).firstTurnId();
        for (ContextAudit.CoverageRange range : ranges) {
            if (range.firstTurnId() != expected) return false;
            expected = range.lastTurnId() + 1;
        }
        return expected > throughTurnId;
    }

    /** 任何一段 coverage 越过检查点自己声明的 through_turn_id 都算越界：那是没检查过的轮次。 */
    private static boolean overrunsCheckedBound(List<ContextAudit.CoverageRange> ranges, long throughTurnId) {
        for (ContextAudit.CoverageRange range : ranges) {
            if (range.lastTurnId() > throughTurnId) return true;
        }
        return false;
    }

    private static boolean isCovered(ContextAudit.Summary summary, long turnId) {
        if (summary == null) return false;
        for (ContextAudit.CoverageRange range : summary.coverageRanges()) {
            if (turnId >= range.firstTurnId() && turnId <= range.lastTurnId()) return true;
        }
        return false;
    }

    /**
     * 中断续接状态（§4.5 尾段）：只看当前 generation、本轮之前<b>最新一条</b>终态；
     * 它是 COMPLETED 就当没有中断，不往前翻历史失败。
     */
    private ContextAudit.ResumeState resumeState(Request request) throws SQLException {
        Optional<TurnHistory> latest = history.latestTerminalBefore(request.sessionId(), request.currentTurnId(),
                request.contextGeneration());
        if (latest.isEmpty() || ChatTurnRepository.STATUS_COMPLETED.equals(latest.get().status())) return null;
        return new ContextAudit.ResumeState(latest.get().requestId(), latest.get().status(),
                latest.get().finishedAt());
    }
}
