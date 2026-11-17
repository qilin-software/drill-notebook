package com.drillnotebook.app.model;

/** Transport attempts are evidence of dispatch, never confirmation that a provider received the request. */
public record AiRequestDiagnostics(Stage stage, boolean mainRequestAttempted, boolean compactionRequestAttempted) {
    public enum Stage { PREPARING, COMPACTING, SENDING, TOOLS, FINALIZING }
    public static final AiRequestDiagnostics INITIAL = new AiRequestDiagnostics(Stage.PREPARING, false, false);

    public AiRequestDiagnostics withStage(Stage value) {
        return new AiRequestDiagnostics(value, mainRequestAttempted, compactionRequestAttempted);
    }

    public AiRequestDiagnostics attempted(String kind) {
        return new AiRequestDiagnostics(stage, mainRequestAttempted || "CHAT".equals(kind),
                compactionRequestAttempted || "CONTEXT_COMPACT".equals(kind));
    }
}
