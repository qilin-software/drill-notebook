package com.drillnotebook.app.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Model-view provenance. Internal tags are removed before protocol serialization. */
public record ContextSource(String kind, String id, long turnId, String requestId, long revision,
                            String fingerprint) {
    public ContextSource {
        if (!java.util.Set.of("TURN", "STEP", "LEGACY", "ANCHOR").contains(kind)
                || id == null || id.isBlank() || requestId == null || requestId.isBlank()
                || revision < 1 || turnId < 0 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("上下文来源身份无效");
    }
    public static final String TAG = "_contextSource";

    public String key() { return kind + ":" + id; }

    public ContextAudit.Selected selected() {
        String version = revision + "@" + fingerprint;
        String raw = kind + '|' + id + '|' + version + '|' + turnId;
        try {
            String key = "sk" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
            return new ContextAudit.Selected(key, kind, id, version, turnId == 0 ? null : turnId);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public Map<String, Object> tag(Map<String, Object> message) {
        var copy = new LinkedHashMap<>(message);
        copy.put(TAG, Map.of("kind", kind, "id", id, "turnId", turnId, "requestId", requestId,
                "revision", revision, "fingerprint", fingerprint));
        return copy;
    }

    public static ContextSource from(Map<String, Object> message) {
        if (!(message.get(TAG) instanceof Map<?, ?> value)) return null;
        try {
            return new ContextSource((String) value.get("kind"), (String) value.get("id"),
                    ((Number) value.get("turnId")).longValue(), (String) value.get("requestId"),
                    ((Number) value.get("revision")).longValue(), (String) value.get("fingerprint"));
        } catch (RuntimeException error) { throw new IllegalArgumentException("上下文来源身份无效", error); }
    }

    public static Map<String, Object> untag(Map<String, Object> message) {
        var copy = new LinkedHashMap<>(message);
        copy.remove(TAG);
        copy.remove("_contextUserText");
        // Anthropic signed state is translated by its adapter, never sent as an OpenAI extension.
        return copy;
    }
}
