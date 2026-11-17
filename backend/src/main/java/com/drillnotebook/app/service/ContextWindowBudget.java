package com.drillnotebook.app.service;

import com.drillnotebook.app.model.AiContextSettings;
import java.util.Map;

/** The same final output allocation is used for preflight and HTTP serialization. */
public record ContextWindowBudget(int window, int outputReserve, int safetyMargin, int hardInput, int softInput, int targetInput) {
    public static ContextWindowBudget of(int window, int output, AiContextSettings settings) {
        int margin = Math.max(ContextBudgeter.MIN_SAFETY_MARGIN_TOKENS, window / 10);
        int hard = window - output - margin;
        if (hard <= 0) throw new ChatTurnRejectException(413, "CONTEXT_TOO_LARGE", "模型窗口不足以容纳输出预留和安全余量", Map.of("contextWindow", window, "outputReserve", output, "safetyMargin", margin));
        return new ContextWindowBudget(window, output, margin, hard,
                Math.min((int) ((long) window * settings.thresholdPercent() / 100), hard),
                Math.min((int) ((long) window * AiContextSettings.TARGET_PERCENT / 100), hard));
    }

    public void requireFits(int input) {
        if (input > hardInput) throw new ChatTurnRejectException(413, "CONTEXT_TOO_LARGE",
                "当前提问、必要规则或最近工具步骤超出模型窗口，请缩短输入或选择更大的模型", Map.of("estimatedInputTokens", input,
                "inputBudget", hardInput, "contextWindow", window, "outputReserve", outputReserve, "safetyMargin", safetyMargin));
    }

    /** A provider refusal overrides the local estimate for this turn; it never expands the configured window. */
    public ContextWindowBudget reducedTo(int ceiling) {
        if (ceiling <= 0) return this;
        int hard = Math.min(hardInput, ceiling);
        return new ContextWindowBudget(window, outputReserve, safetyMargin, hard,
                Math.min(softInput, hard), Math.min(targetInput, hard));
    }
}
