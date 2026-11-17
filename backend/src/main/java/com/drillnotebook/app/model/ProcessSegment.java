package com.drillnotebook.app.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次以工具调用结束的模型响应留下的思考与过渡正文，仅用于界面展示过程。
 * anchor 是步骤结束时已有的工具活动数；不进入发给模型的消息，也不作为上下文来源。
 */
public record ProcessSegment(int anchor, String reasoning, String text, boolean truncated) {
    public static final int MAX_ENTRIES = 256;
    /** 全部步骤的思考与正文合计上限（UTF-8 字节）。 */
    public static final int MAX_TOTAL_BYTES = 65_536;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ProcessSegment {
        anchor = Math.max(0, Math.min(ToolActivity.MAX_ENTRIES, anchor));
        reasoning = reasoning == null ? "" : reasoning;
        text = text == null ? "" : text;
    }

    /**
     * 追加一个步骤；思考和正文都为空白时原样返回。超出上限优先保留正文，再保留思考，
     * 已无空间时只把最后一步标为截断。
     */
    public static List<ProcessSegment> append(List<ProcessSegment> current, int anchor, String reasoning, String text) {
        String thought = reasoning == null ? "" : reasoning;
        String body = text == null ? "" : text;
        if (thought.isBlank() && body.isBlank()) return current;
        int left = MAX_TOTAL_BYTES;
        for (ProcessSegment segment : current) left -= bytes(segment.reasoning()) + bytes(segment.text());
        var next = new ArrayList<>(current);
        if (next.size() >= MAX_ENTRIES || left <= 0) {
            if (next.isEmpty() || next.get(next.size() - 1).truncated()) return current;
            ProcessSegment last = next.get(next.size() - 1);
            next.set(next.size() - 1, new ProcessSegment(last.anchor(), last.reasoning(), last.text(), true));
            return List.copyOf(next);
        }
        String keptBody = utf8Prefix(body, left);
        String keptThought = utf8Prefix(thought, left - bytes(keptBody));
        next.add(new ProcessSegment(anchor, keptThought, keptBody,
                keptBody.length() < body.length() || keptThought.length() < thought.length()));
        return List.copyOf(next);
    }

    /** 历史与恢复只取已知字段；损坏或越界的元数据按没有步骤处理。 */
    public static List<ProcessSegment> readMeta(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode rows = JSON.readTree(json).path("processSegments");
            if (!rows.isArray()) return List.of();
            List<ProcessSegment> result = List.of();
            for (JsonNode row : rows) {
                if (result.size() >= MAX_ENTRIES) break;
                if (!row.path("anchor").isIntegralNumber()) continue;
                var appended = append(result, row.path("anchor").asInt(), row.path("reasoning").asText(""), row.path("text").asText(""));
                if (appended == result) continue;
                if (row.path("truncated").asBoolean(false) && !appended.get(appended.size() - 1).truncated()) {
                    var copy = new ArrayList<>(appended);
                    ProcessSegment last = copy.get(copy.size() - 1);
                    copy.set(copy.size() - 1, new ProcessSegment(last.anchor(), last.reasoning(), last.text(), true));
                    appended = List.copyOf(copy);
                }
                result = appended;
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String utf8Prefix(String value, int bytes) {
        int end = 0, used = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int size = codePoint <= 127 ? 1 : codePoint <= 2047 ? 2 : codePoint <= 65535 ? 3 : 4;
            if (used + size > bytes) break;
            used += size;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }
}
