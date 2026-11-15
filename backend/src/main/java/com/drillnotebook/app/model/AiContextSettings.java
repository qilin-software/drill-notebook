package com.drillnotebook.app.model;

/** 会话压缩与长期记忆分别控制，设置随一轮受理快照冻结。 */
public record AiContextSettings(boolean autoCompact, int thresholdPercent) {
    public static final AiContextSettings DEFAULTS = new AiContextSettings(true, 80);
    public static final int TARGET_PERCENT = 60;

    public AiContextSettings {
        if (thresholdPercent < 65 || thresholdPercent > 95)
            throw new IllegalArgumentException("上下文压缩阈值须为 65–95 的整数");
    }
}
