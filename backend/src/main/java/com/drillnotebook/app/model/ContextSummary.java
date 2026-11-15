package com.drillnotebook.app.model;

import java.util.List;

/** Generic continuation state, separate from persistent personal memory. */
public record ContextSummary(String goal, List<String> constraints, List<String> decisions,
                             List<String> completed, List<String> pending, List<String> unknown,
                             List<String> sources, List<String> receipts) {
    public ContextSummary {
        if (goal == null || goal.isBlank() || goal.length() > 4096) throw new IllegalArgumentException("摘要目标缺失或过长");
        constraints = check(constraints); decisions = check(decisions); completed = check(completed);
        pending = check(pending); unknown = check(unknown); sources = check(sources, 512); receipts = check(receipts, 1024);
    }
    private static List<String> check(List<String> values) {
        return check(values, 64);
    }
    private static List<String> check(List<String> values, int limit) {
        if (values == null || values.size() > limit || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 4096)
                || values.stream().distinct().count() != values.size())
            throw new IllegalArgumentException("摘要字段缺失或超过安全范围");
        return List.copyOf(values);
    }
}
