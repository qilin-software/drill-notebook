package com.drillnotebook.app.model;

import java.util.List;

/** Deterministic evidence kept outside the lossy model summary, encrypted with its checkpoint. */
public record ContextRetainedState(List<UserExcerpt> userExcerpts, List<Receipt> receipts) {
    public static final ContextRetainedState EMPTY = new ContextRetainedState(List.of(), List.of());
    public record UserExcerpt(String source, int messageIndex, String text) {
        public UserExcerpt {
            if (source == null || source.isBlank() || source.length() > 256 || messageIndex < 0 || text == null || text.isBlank() || text.length() > 2048)
                throw new IllegalArgumentException("用户原话保护条目无效");
        }
    }
    public record Receipt(String source, String id, String tool, String status) {
        public Receipt {
            if (source == null || source.isBlank() || source.length() > 256 || id == null || !id.startsWith("CALL:") || id.length() > 1024
                    || tool == null || tool.isBlank() || tool.length() > 128 || status == null
                    || !java.util.Set.of("CREATED", "UPDATED", "DELETED", "REMOVED", "DUPLICATE", "NO_CHANGE", "SENT", "QUEUED", "RUNNING").contains(status))
                throw new IllegalArgumentException("写入回执无效");
        }
    }
    public ContextRetainedState {
        if (userExcerpts == null || receipts == null || userExcerpts.size() > 512 || receipts.size() > 65536
                || userExcerpts.stream().anyMatch(java.util.Objects::isNull) || receipts.stream().anyMatch(java.util.Objects::isNull)
                || userExcerpts.stream().distinct().count() != userExcerpts.size()
                || receipts.stream().map(Receipt::id).distinct().count() != receipts.size())
            throw new IllegalArgumentException("上下文保护状态超过安全范围");
        userExcerpts = List.copyOf(userExcerpts); receipts = List.copyOf(receipts);
    }
}
