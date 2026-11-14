package com.drillnotebook.app.service;

import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.model.RequestContextAudit.Kind;
import java.util.EnumMap;
import java.util.Map;

/** 当前主模型请求及其响应的数值快照；不含提示词、工具正文或附件内容。 */
public record ModelContextSnapshot(int schemaVersion, String estimatorVersion, String model, int contextWindow,
                                   long contextGeneration, long historyRevision, Map<Kind, Long> inputCategories,
                                   long outputTokens, boolean complete) {
    public static final int VERSION = 1;

    public ModelContextSnapshot {
        if (schemaVersion != VERSION || !TokenEstimator.VERSION.equals(estimatorVersion)
                || model == null || model.isBlank() || model.length() > 256 || contextWindow < 0
                || contextGeneration < 1 || historyRevision < 0 || outputTokens < 0)
            throw new IllegalArgumentException("模型上下文快照无效");
        inputCategories = new ContextTokenBreakdown(inputCategories).categories();
        if (new ContextTokenBreakdown(inputCategories).tokens() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("模型请求上下文超出数值范围");
    }

    public static ModelContextSnapshot from(RequestContextAudit input, long generation, long revision) {
        if (!"CHAT".equals(input.kind())) throw new IllegalArgumentException("辅助请求不能替代主模型上下文");
        var categories = new EnumMap<Kind, Long>(Kind.class);
        for (var category : input.categories()) categories.put(category.kind(), (long) category.estimatedTokens());
        return new ModelContextSnapshot(VERSION, input.estimatorVersion(), input.model(), input.contextWindow(),
                generation, revision, categories, 0, true);
    }

    public ModelContextSnapshot withOutput(long tokens) {
        return new ModelContextSnapshot(schemaVersion, estimatorVersion, model, contextWindow,
                contextGeneration, historyRevision, inputCategories, tokens, complete);
    }

    public ModelContextSnapshot atRevision(long revision) {
        return new ModelContextSnapshot(schemaVersion, estimatorVersion, model, contextWindow,
                contextGeneration, revision, inputCategories, outputTokens, complete);
    }

    public ModelContextSnapshot incomplete() {
        return new ModelContextSnapshot(schemaVersion, estimatorVersion, model, contextWindow,
                contextGeneration, historyRevision, inputCategories, outputTokens, false);
    }

    public ContextTokenBreakdown breakdown() {
        var result = new ContextTokenBreakdown.Builder();
        result.add(new ContextTokenBreakdown(inputCategories));
        result.add(Kind.ASSISTANT, outputTokens);
        return result.build();
    }
}
