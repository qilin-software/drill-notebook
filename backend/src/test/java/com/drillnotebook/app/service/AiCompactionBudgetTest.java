package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.model.AiRuntimeSettings;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AiCompactionBudgetTest {
    @Test void preparationHasABoundedTotalAndPerRequestDeadlineWithoutExpiringTheAnswer() {
        var clock = new AtomicLong();
        var turn = new AiTurnBudget(AiRuntimeSettings.DEFAULTS, clock::get);
        var compact = new AiCompactionBudget(turn, 8192, clock::get);
        assertEquals(120_000, compact.millisLeft());
        assertEquals(60, compact.requestSeconds());
        clock.addAndGet(70_000_000_000L);
        assertEquals(50_000, compact.millisLeft());
        assertEquals(50, compact.requestSeconds());
        clock.addAndGet(50_000_000_000L);
        assertEquals(0, compact.requestSeconds());
        assertTrue(turn.requestSeconds() > 0, "压缩的期限不能取消或耗尽主回答");
    }

    @Test void aLatePreparationKeepsTheAnswerTimeReserve() {
        var clock = new AtomicLong();
        var turn = new AiTurnBudget(AiRuntimeSettings.DEFAULTS, clock::get);
        clock.set((30 * 60L - 90) * 1_000_000_000L);
        var compact = new AiCompactionBudget(turn, 8192, clock::get);
        assertEquals(60, compact.requestSeconds());
        clock.addAndGet(68_000_000_000L);
        assertEquals(0, compact.millisLeft());
        assertEquals(22, turn.requestSeconds());
    }

    @Test void unknownSummaryUsageIsConservativeAndStillLeavesTheReservedAnswerOutput() {
        var settings = new AiRuntimeSettings(5, 10, 2, 1, 1024, 512, 256, 0);
        var turn = new AiTurnBudget(settings);
        var compact = new AiCompactionBudget(turn, 256);
        assertEquals(512, compact.allocation(512));
        int reserved = turn.reserve(512);
        turn.consume(AiUpstreamClient.Usage.unknown(), reserved);
        assertEquals(256, compact.allocation(512));
        assertEquals(512, turn.allocation(512, 0));
        int next = turn.reserve(compact.allocation(512));
        turn.consume(new AiUpstreamClient.Usage("provider", 100, 50, 0, 0), next);
        assertEquals(206, compact.allocation(512));
        assertEquals(462, turn.allocation(512, 0));
    }
}
