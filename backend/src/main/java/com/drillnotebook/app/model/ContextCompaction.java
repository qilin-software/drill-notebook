package com.drillnotebook.app.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Progress is transient; completed is set only after a validated checkpoint was published. */
public record ContextCompaction(boolean running, boolean completed) {
    public static final ContextCompaction NONE = new ContextCompaction(false, false);

    public ContextCompaction withRunning(boolean value) { return new ContextCompaction(value, completed); }
    public ContextCompaction succeeded() { return new ContextCompaction(false, true); }
    public ContextCompaction settled() { return new ContextCompaction(false, completed); }

    public static ContextCompaction read(JsonNode node) {
        return node != null && node.isObject() && node.path("completed").isBoolean() && node.path("completed").booleanValue()
                ? new ContextCompaction(false, true) : NONE;
    }
}
