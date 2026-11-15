package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.service.ChatContextAssembler.Draft;
import com.drillnotebook.app.service.ChatContextAssembler.Image;
import com.drillnotebook.app.service.ChatContextAssembler.Item;
import com.drillnotebook.app.service.ChatContextAssembler.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Candidate view assembly; final protocol preflight in ContextCoordinator owns window validation. */
@Component
public class ContextBudgeter {
    /** 输出预留沿用现有默认 max_tokens。 */
    public static final int OUTPUT_RESERVE_TOKENS = 4096;
    public static final int MIN_SAFETY_MARGIN_TOKENS = 512;

    private final TokenEstimator estimator;
    private final ObjectMapper mapper;

    public ContextBudgeter(TokenEstimator estimator, ObjectMapper mapper) {
        this.estimator = estimator;
        this.mapper = mapper;
    }

    int estimate(List<Map<String, Object>> messages) { return estimator.estimateInput(messages.stream().map(com.drillnotebook.app.model.ContextSource::untag).toList()); }

    /** 计划身份、已配置的厂商窗口与本轮输出预留。 */
    public record Target(String planId, long sessionId, long contextGeneration, long historyRevision,
                         Long summaryRevision, Long vaultEpoch, Long vaultRevision, long policyRevision,
                          int contextWindow, boolean windowKnown, int outputReserve,
                           String providerFingerprint, String protocol, String model, boolean memoriesAllowed, String memoryBoundary) {
        public Target(String planId, long sessionId, long contextGeneration, long historyRevision,
                      Long summaryRevision, Long vaultEpoch, Long vaultRevision, long policyRevision,
                      int contextWindow, boolean windowKnown, int outputReserve,
                      String providerFingerprint, String protocol, String model) {
            this(planId, sessionId, contextGeneration, historyRevision, summaryRevision, vaultEpoch, vaultRevision,
                    policyRevision, contextWindow, windowKnown, outputReserve, providerFingerprint, protocol, model, true, null);
        }
        public Target(String planId, long sessionId, long contextGeneration, long historyRevision,
                      Long summaryRevision, Long vaultEpoch, Long vaultRevision, long policyRevision,
                      int contextWindow, boolean windowKnown, int outputReserve) {
            this(planId, sessionId, contextGeneration, historyRevision, summaryRevision, vaultEpoch, vaultRevision,
                    policyRevision, contextWindow, windowKnown, outputReserve, "", "", "");
        }
        public Target(String planId, long sessionId, long contextGeneration, long historyRevision,
                      Long summaryRevision, Long vaultEpoch, Long vaultRevision, long policyRevision,
                      int contextWindow, boolean windowKnown) {
            this(planId, sessionId, contextGeneration, historyRevision, summaryRevision, vaultEpoch, vaultRevision,
                    policyRevision, contextWindow, windowKnown, OUTPUT_RESERVE_TOKENS);
        }
    }

    /** Candidate work set; serialization and provenance read the same items. */
    private static final class Kept {
        private Item summary;
        private Item resumeState;
        private final List<Item> memories = new ArrayList<>();
        private final List<Turn> turns = new ArrayList<>();
        private final List<Item> page = new ArrayList<>();
        private final List<Item> rag = new ArrayList<>();
        private final List<RetrievalHit> hits = new ArrayList<>();
        private final List<Item> attachments = new ArrayList<>();
        private final List<Image> currentImages = new ArrayList<>();

        /** 本轮要发的全部图片：当前轮的 + 仍在上下文里的历史轮的，计费与审计都按这份算。 */
        List<Image> allImages() {
            List<Image> images = new ArrayList<>(currentImages);
            for (Turn turn : turns) images.addAll(turn.images());
            return images;
        }

        /** 图片按"未知成本每张预留 4,096"计（§4.3），参与统一窗口预算。 */
        int imageTokens() {
            return allImages().size() * TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS;
        }
    }

    public ChatContextPlan fit(Draft draft, ConversationProjection.Projection projection, Target target,
                               ContextAudit.Builder audit) {
        Kept kept = new Kept();
        kept.summary = draft.summary();
        kept.resumeState = draft.resumeState();
        kept.memories.addAll(draft.memories());
        kept.turns.addAll(draft.recentTurns());
        kept.page.addAll(draft.pageParts());
        kept.rag.addAll(draft.ragExcerpts());
        kept.hits.addAll(draft.ragHits());
        kept.attachments.addAll(draft.textAttachments());
        kept.currentImages.addAll(draft.currentImages());

        List<String> notices = new ArrayList<>(draft.notices());
        if (!target.windowKnown() || target.contextWindow() <= 0) throw new ChatTurnRejectException(400,
                "MODEL_LIMITS_REQUIRED", "请先在模型设置中填写上下文窗口和最大输出 Token", Map.of());
        if (!kept.currentImages.isEmpty() || kept.turns.stream().anyMatch(turn -> !turn.images().isEmpty())) {
            notices.add("IMAGE_COST_UNKNOWN");
        }
        int limit = ContextWindowBudget.of(target.contextWindow(), target.outputReserve(), com.drillnotebook.app.model.AiContextSettings.DEFAULTS).hardInput();
        if (kept.summary == null && !projection.coveredCandidates().isEmpty()) restoreCovered(kept, projection, audit);
        // Candidate only: the protocol adapter adds tools, signed state and actual output allocation.
        // Final PreparedRequest preflight owns the window budget and compaction; no history or current attachments are silently removed here.
        List<Map<String, Object>> messages = serialize(draft, kept, target);
        int estimated = estimator.estimateInput(messages.stream().map(com.drillnotebook.app.model.ContextSource::untag).toList());

        List<Map<String, Object>> citations = new ArrayList<>();
        for (int index = 0; index < kept.hits.size(); index++) {
            citations.add(citationView(kept.hits.get(index).citation(), index + 1));
        }
        buildAudit(audit, kept, draft, projection, estimated, limit);
        return new ChatContextPlan(target.planId(), target.sessionId(), target.contextGeneration(),
                target.historyRevision(), target.summaryRevision(), target.vaultEpoch(), target.vaultRevision(),
                target.policyRevision(), messages, notices, citations,
                audit.build(ContextAudit.DISPATCH_NOT_SENT, estimated, limit, projection.scan(),
                        kept.summary == null ? null : projection.summary().audit(),
                        projection.resumeState()));
    }

    /** Restore original sources when a legacy summary was not eligible. */
    private void restoreCovered(Kept kept, ConversationProjection.Projection projection,
                                ContextAudit.Builder audit) {
        for (ConversationProjection.Item item : projection.coveredCandidates()) {
            Turn turn = new Turn(item.turnId(), false, item.userMessageId(), item.assistantMessageId(),
                    item.userText(), item.assistantText(), List.of(),
                    item.estimatedTokens() + 2 * TokenEstimator.MESSAGE_OVERHEAD_TOKENS,item.supplements(), item.steps(), item.sourceRevision(), item.requestId());
            int position = 0;
            while (position < kept.turns.size()
                    && (kept.turns.get(position).turnId() == null
                    || kept.turns.get(position).turnId() < turn.turnId())) {
                position++;
            }
            kept.turns.add(position, turn);
        }
        audit.clearOmission(ContextAudit.Omission.COVERED_BY_SUMMARY, "TURN");
    }

    /** 最终消息序列：固定系统前缀 → 一个 CONTEXT_DATA_V1 数据块 → 近期对话 → 当前输入永远最后。 */
    private List<Map<String, Object>> serialize(Draft draft, Kept kept, Target target) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", List.of(textContent(draft.systemText()))));
        Map<String, Object> envelope = ChatContextAssembler.dataEnvelope(kept.summary, kept.resumeState,
                kept.memories, kept.rag, kept.page, kept.attachments);
        messages.add(message("user", List.of(textContent(write(envelope)))));
        for (Turn turn : kept.turns) {
            var source = turn.requestId() == null || turn.requestId().isBlank() ? null : turn.legacy() ? new com.drillnotebook.app.model.ContextSource("LEGACY", turn.userMessageId() + ":" + turn.assistantMessageId(), 0,
                    turn.userMessageId() + ":" + turn.assistantMessageId(), 1, turn.requestId()) : new com.drillnotebook.app.model.ContextSource("TURN", String.valueOf(turn.turnId()),
                    turn.turnId(), turn.requestId(), turn.sourceRevision(), ChatContextEntryService.fingerprint(turn.requestId() + ":" + turn.sourceRevision()));
            List<Map<String, Object>> turnMessages = new ArrayList<>();
            List<Object> userBlocks = new ArrayList<>();
            userBlocks.add(textContent(turn.userText()));
            for (Image image : turn.images()) userBlocks.add(imageContent(image));
            turnMessages.add(message("user", userBlocks));
            int[] replyPosition = {0, 0};
            for (var step : turn.steps()) {
                appendReplyThrough(turnMessages, turn.assistantText(), turn.supplements(), replyPosition, step.replyOffset());
                if (step.replyCharacters() > 0) {
                    int end = Math.addExact(step.replyOffset(), step.replyCharacters());
                    String visible = step.messages().stream().filter(m -> "assistant".equals(m.get("role")) && !m.containsKey("tool_calls"))
                            .map(m -> String.valueOf(m.get("content"))).collect(java.util.stream.Collectors.joining());
                    if (end > turn.assistantText().length() || replyPosition[0] != step.replyOffset()
                            || !visible.equals(turn.assistantText().substring(step.replyOffset(), end)))
                        throw new ChatCryptoException("回复步骤与当前正文版本不一致");
                    replyPosition[0] = end;
                }
                turnMessages.addAll(step.forTarget(target.providerFingerprint(), target.protocol(), target.model(), target.memoriesAllowed(), target.memoryBoundary())
                        .stream().map(com.drillnotebook.app.model.ContextSource::untag).toList());
            }
            appendReplyThrough(turnMessages, turn.assistantText(), turn.supplements(), replyPosition, turn.assistantText().length());
            for (var message : turnMessages) messages.add(source == null ? message : source.tag(message));
        }
        List<Object> current = new ArrayList<>();
        current.add(textContent(draft.userText()));
        for (Image image : kept.currentImages) current.add(imageContent(image));
        messages.add(message("user", current));
        return messages;
    }

    private static void appendReplyThrough(List<Map<String, Object>> messages, String answer, List<ChatSupplement> supplements, int[] state, int requestedEnd) {
        int end = Math.min(answer.length(), Math.max(state[0], requestedEnd));
        while (state[1] < supplements.size() && supplements.get(state[1]).replyOffset() <= end) {
            var supplement = supplements.get(state[1]++);
            int at = Math.min(end, Math.max(state[0], supplement.replyOffset()));
            if (at > state[0]) messages.add(message("assistant", List.of(textContent(answer.substring(state[0], at)))));
            messages.add(message("user", List.of(textContent(supplement.contextText())))); state[0] = at;
        }
        if (end > state[0]) messages.add(message("assistant", List.of(textContent(answer.substring(state[0], end)))));
        state[0] = end;
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private static Map<String, Object> textContent(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /** 图片走 data URL：chat_completions 直接可用，Anthropic 适配器会拆成 base64 source。 */
    private static Map<String, Object> imageContent(Image image) {
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("url", "data:" + image.mimeType() + ";base64," + image.base64());
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image_url");
        block.put("image_url", url);
        return block;
    }

    private String write(Map<String, Object> envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception error) {
            throw new ChatTurnRejectException(500, "CONTEXT_UNSERIALIZABLE", "上下文无法序列化，本轮未外发",
                    Map.of());
        }
    }

    private Map<String, Object> citationView(Citation citation, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("corpusType", citation.corpusType());
        row.put("notebookId", citation.notebookId());
        row.put("pageId", citation.pageId());
        row.put("chunkId", citation.chunkId());
        row.put("title", citation.title());
        row.put("headingPath", citation.headingPath());
        row.put("snippet", citation.snippet());
        row.put("matchTypes", citation.matchTypes());
        row.put("ftsRank", citation.ftsRank());
        row.put("vectorRank", citation.vectorRank());
        row.put("rrfScore", citation.rrfScore());
        return row;
    }

    private void buildAudit(ContextAudit.Builder audit, Kept kept, Draft draft,
                            ConversationProjection.Projection projection, int estimated, int limit) {
        audit.region(ContextAudit.Region.SYSTEM, estimator.estimateText(draft.systemText()), 1);
        audit.region(ContextAudit.Region.MEMORY, sum(kept.memories), kept.memories.size());
        int sessionState = sum(listOfNotNull(kept.summary)) + sum(listOfNotNull(kept.resumeState));
        audit.region(ContextAudit.Region.SESSION_STATE, sessionState,
                (kept.summary == null ? 0 : 1) + (kept.resumeState == null ? 0 : 1));
        audit.region(ContextAudit.Region.RECENT_TURNS, kept.turns.stream().mapToInt(Turn::estimatedTokens).sum(),
                kept.turns.size());
        audit.region(ContextAudit.Region.PAGE, sum(kept.page), kept.page.size());
        audit.region(ContextAudit.Region.NOTEBOOK_RAG, sum(kept.rag), kept.rag.size());
        audit.region(ContextAudit.Region.USER_TEXT, estimator.estimateText(draft.userText()), 1);
        audit.region(ContextAudit.Region.TEXT_ATTACHMENT, sum(kept.attachments), kept.attachments.size());
        audit.region(ContextAudit.Region.IMAGE, kept.imageTokens(), kept.allImages().size());

        if (kept.summary != null) {
            // 摘要的权威版本就是检查点 revision：放进 selected.version，检查器据此判断"是否已被替换"
            audit.select(selected("SUMMARY", kept.summary.id(), kept.summary.id(), null));
        }
        if (kept.resumeState != null) {
            audit.select(selected("RESUME_STATE", kept.resumeState.id(), kept.resumeState.version(), null));
        }
        for (Item memory : kept.memories) {
            audit.select(selected("MEMORY", memory.id(), memory.version(), null));
        }
        for (Item excerpt : kept.rag) {
            audit.select(selected("NOTEBOOK_RAG", excerpt.id(), excerpt.version(), null));
        }
        for (Item page : kept.page) {
            audit.select(selected("PAGE", page.id(), page.version(), null));
        }
        for (Item attachment : kept.attachments) {
            audit.select(selected("TEXT_ATTACHMENT", attachment.id(), attachment.version(), null));
        }
        for (Image image : kept.allImages()) {
            audit.select(selected("IMAGE", image.attachmentId(), image.blockId(), image.sourceTurnId()));
        }
        for (Turn turn : kept.turns) {
            if (turn.turnId() == null) {
                audit.select(selected("LEGACY_MESSAGE", String.valueOf(turn.assistantMessageId()), null, null));
            } else {
                audit.select(selected("TURN", String.valueOf(turn.turnId()), null, turn.turnId()));
            }
        }
    }

    private static List<Item> listOfNotNull(Item item) {
        return item == null ? List.of() : List.of(item);
    }

    private static int sum(List<Item> items) {
        int total = 0;
        for (Item item : items) total += item.estimatedTokens();
        return total;
    }

    private static ContextAudit.Selected selected(String kind, String id, String version, Long sourceTurnId) {
        return new ContextAudit.Selected(sourceKey(kind, id, version, sourceTurnId), kind, id, version, sourceTurnId);
    }

    /** 来源选择键：本轮 plan 内的稳定标识，预览接口先验它属于 selected 才给片段。 */
    private static String sourceKey(String kind, String id, String version, Long sourceTurnId) {
        String raw = kind + '|' + id + '|' + (version == null ? "" : version) + '|'
                + (sourceTurnId == null ? "" : sourceTurnId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "sk" + java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

}
