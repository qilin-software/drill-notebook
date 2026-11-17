package com.drillnotebook.app.service;

import java.util.function.LongSupplier;

/** A bounded preparation workload that always leaves time and output for the user's answer. */
final class AiCompactionBudget {
    static final int MAX_SECONDS = 120;
    static final int MAX_REQUEST_SECONDS = 60;
    private final AiTurnBudget turn;
    private final int answerOutput;
    private final int answerSeconds;
    private final LongSupplier clock;
    private final long started;

    AiCompactionBudget(AiTurnBudget turn, int answerOutput) { this(turn, answerOutput, System::nanoTime); }
    AiCompactionBudget(AiTurnBudget turn, int answerOutput, LongSupplier clock) {
        this.turn = turn;
        this.answerOutput = answerOutput;
        this.answerSeconds = Math.min(60, Math.max(5, turn.requestSeconds() / 4));
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    int allocation(int requested) { return turn.allocation(requested, answerOutput); }
    long millisLeft() {
        long local = Math.max(0, MAX_SECONDS * 1000L - Math.max(0, clock.getAsLong() - started) / 1_000_000L);
        long shared = Math.max(0, turn.secondsLeft() - answerSeconds) * 1000;
        return Math.min(local, shared);
    }
    int requestSeconds() { return (int) Math.min(MAX_REQUEST_SECONDS, Math.min(turn.requestSeconds(), millisLeft() / 1000)); }
}
