package com.drillnotebook.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessSegmentTest {

    @Test
    void 思考与正文都为空白时不产生步骤() {
        List<ProcessSegment> current = List.of();
        assertSame(current, ProcessSegment.append(current, 0, "  ", null));
    }

    @Test
    void 追加保留顺序与锚点() {
        var first = ProcessSegment.append(List.of(), 0, "先想一下", "我先查一下笔记");
        var second = ProcessSegment.append(first, 2, "", "再搜索一次");
        assertEquals(2, second.size());
        assertEquals(new ProcessSegment(0, "先想一下", "我先查一下笔记", false), second.get(0));
        assertEquals(new ProcessSegment(2, "", "再搜索一次", false), second.get(1));
    }

    @Test
    void 超出上限时优先保留正文且不截断半个字符() {
        String text = "文".repeat(ProcessSegment.MAX_TOTAL_BYTES / 3 - 1);
        var segments = ProcessSegment.append(List.of(), 0, "思考".repeat(10), text);
        ProcessSegment only = segments.get(0);
        assertEquals(text, only.text());
        assertTrue(only.truncated());
        assertTrue(only.reasoning().length() < 20);
        int bytes = (only.text() + only.reasoning()).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= ProcessSegment.MAX_TOTAL_BYTES);
        assertEquals(only.reasoning(), new String(only.reasoning().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void 空间用尽后只把最后一步标为截断() {
        var full = ProcessSegment.append(List.of(), 0, "", "a".repeat(ProcessSegment.MAX_TOTAL_BYTES));
        assertFalse(full.get(0).truncated());
        var marked = ProcessSegment.append(full, 1, "", "更多内容");
        assertEquals(1, marked.size());
        assertTrue(marked.get(0).truncated());
        assertSame(marked, ProcessSegment.append(marked, 2, "", "还有"));
    }

    @Test
    void 读取元数据只取合法步骤() {
        String json = """
                {"toolActivities":[],"processSegments":[
                  {"anchor":1,"reasoning":"想","text":"过渡","truncated":true},
                  {"anchor":"x","text":"非法锚点"},
                  {"anchor":5000,"text":"越界锚点收敛"},
                  {"anchor":2,"reasoning":"","text":""}
                ]}""";
        var segments = ProcessSegment.readMeta(json);
        assertEquals(List.of(new ProcessSegment(1, "想", "过渡", true),
                new ProcessSegment(ToolActivity.MAX_ENTRIES, "", "越界锚点收敛", false)), segments);
        assertEquals(List.of(), ProcessSegment.readMeta("{\"processSegments\":\"oops\"}"));
        assertEquals(List.of(), ProcessSegment.readMeta("not json"));
        assertEquals(List.of(), ProcessSegment.readMeta(null));
    }
}
