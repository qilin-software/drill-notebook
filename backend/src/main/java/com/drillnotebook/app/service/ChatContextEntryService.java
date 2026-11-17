package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ContextSource;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatContextEntryRepository;
import com.drillnotebook.app.repository.ChatContextEntryRepository.Entry;
import com.drillnotebook.app.repository.ChatStorageSizer;
import com.drillnotebook.app.repository.UsageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Saves only bounded messages that form model-visible steps; ordinary thinking stays in the UI record. */
@Service
public class ChatContextEntryService {
    public static final String MEMORY_REDACTED_TAG = "_contextMemoryRedacted";
    public record Step(Entry entry, List<Map<String, Object>> messages, int replyOffset, int replyCharacters, String memoryBoundary) {
        public Step(Entry entry, List<Map<String, Object>> messages) { this(entry, messages, 0, 0, null); }
        public Step(Entry entry, List<Map<String, Object>> messages, int replyOffset, int replyCharacters) { this(entry, messages, replyOffset, replyCharacters, null); }
        public Step { messages = List.copyOf(messages); }
        public ContextSource source() { return new ContextSource("STEP", entry.id(), entry.turnId(), entry.requestId(), entry.revision(), entry.fingerprint()); }
        public List<Map<String, Object>> forCurrent() {
            return messages.stream().map(message -> { var copy = new LinkedHashMap<>(message); copy.put(ContextCoordinator.ENTRY_TAG, entry.id());
                copy.put(ContextCoordinator.STEP_KIND_TAG, entry.kind()); return source().tag(copy); }).toList();
        }
        public List<Map<String, Object>> forTarget(String provider, String protocol, String model) {
            return forTarget(provider, protocol, model, true, memoryBoundary);
        }
        public List<Map<String, Object>> forTarget(String provider, String protocol, String model, boolean memoriesAllowed, String currentBoundary) {
            boolean compatible = entry.providerFingerprint().equals(provider) && entry.protocol().equals(protocol) && entry.model().equals(model);
            var allowedMessages = !memoriesAllowed || memoryBoundary != null && !memoryBoundary.equals(currentBoundary)
                    ? withoutMemoryContents(messages) : messages;
            return allowedMessages.stream().map(message -> {
                var copy = new LinkedHashMap<>(message);
                copy.put(ContextCoordinator.ENTRY_TAG, entry.id());
                copy.put(ContextCoordinator.STEP_KIND_TAG, entry.kind());
                copy.remove("reasoning_content");
                if (!compatible) copy.remove("anthropic_thinking");
                return source().tag(copy);
            }).toList();
        }
    }
    private final ChatContextEntryRepository repository;
    private final ChatMessageCipher cipher;
    private final ObjectMapper mapper;
    private final TokenEstimator estimator;
    static final int MAX_STEP_BYTES = 2 * 1024 * 1024;

    public ChatContextEntryService(ChatContextEntryRepository repository, ChatMessageCipher cipher, ObjectMapper mapper, TokenEstimator estimator) {
        this.repository = repository; this.cipher = cipher; this.mapper = mapper; this.estimator = estimator;
    }

    public Step save(ChatTurnService.Lease lease, AiUpstreamClient.Upstream target, long generation, String kind,
                     List<Map<String, Object>> messages, boolean closed, Step previous) {
        return save(lease, target, generation, kind, messages, closed, previous,
                previous == null ? 0 : previous.replyOffset(), previous == null ? 0 : previous.replyCharacters());
    }

    public Step save(ChatTurnService.Lease lease, AiUpstreamClient.Upstream target, long generation, String kind,
                      List<Map<String, Object>> messages, boolean closed, Step previous, int replyOffset, int replyCharacters) {
        if (replyOffset < 0 || replyCharacters < 0) throw new IllegalArgumentException("回复步骤位置无效");
        validateSequence(messages, closed);
        try {
            String id = previous == null ? UuidV7.generate(System.currentTimeMillis()) : previous.entry().id();
            int sequence = previous == null ? repository.nextSequence(lease.turnId()) : previous.entry().sequence();
            long revision = previous == null ? 1 : previous.entry().revision() + 1;
            var payload = new LinkedHashMap<String, Object>();
            payload.put("schemaVersion", 1); payload.put("entryId", id); payload.put("sessionId", lease.sessionId());
            payload.put("turnId", lease.turnId()); payload.put("requestId", lease.requestId()); payload.put("revision", revision);
            var storedMessages = messages.stream().map(message -> { var value = ContextSource.untag(message); value.remove("reasoning_content"); value.remove(ContextCoordinator.ENTRY_TAG); return value; }).toList();
            payload.put("messages", storedMessages);
            payload.put("replyOffset", replyOffset); payload.put("replyCharacters", replyCharacters);
            String memoryBoundary = previous == null ? (usesMemory(messages) ? repository.memoryBoundary() : null) : previous.memoryBoundary();
            payload.put("memoryBoundary", memoryBoundary);
            String json = mapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_STEP_BYTES)
                throw new ChatTurnRejectException(413, "CONTEXT_STEP_TOO_LARGE", "工具步骤超出安全保存范围，本轮已停止", Map.of());
            var encrypted = cipher.encrypt(json, lease.masterPassword());
            String provider = UsageRepository.targetFingerprint(target.baseUrl(), target.apiFormat(), target.model());
            Entry entry = new Entry(id, lease.sessionId(), lease.turnId(), lease.requestId(), generation, sequence,
                    revision, kind, closed ? "CLOSED" : "OPEN", provider, target.apiFormat(), target.model(),
                    encrypted.cipher(), encrypted.meta(), fingerprint(json), estimator.estimateInput(storedMessages),
                    ChatStorageSizer.storedBytes(encrypted.cipher(), encrypted.meta()), Instant.now().toString());
            repository.save(entry, previous == null ? null : previous.entry().revision());
            return new Step(entry, messages, replyOffset, replyCharacters, memoryBoundary);
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("上下文步骤无法保存，本轮已停止", error);
        }
    }

    public List<Step> read(long turnId, String password) throws SQLException {
        return read(turnId, password, java.util.Set.of());
    }

    public List<Step> read(long turnId, String password, java.util.Set<String> excludedEntries) throws SQLException {
        var result = new ArrayList<Step>();
        for (Entry entry : repository.readUncoveredTurn(turnId, excludedEntries)) {
            if (!"CLOSED".equals(entry.state()) || excludedEntries.contains(entry.id())) continue;
            result.add(decode(entry, password));
        }
        return List.copyOf(result);
    }

    public java.util.Optional<Step> readEntry(String id, String password) throws SQLException {
        var row = repository.find(id);
        return row.isEmpty() || !"CLOSED".equals(row.get().state()) ? java.util.Optional.empty() : java.util.Optional.of(decode(row.get(), password));
    }

    private Step decode(Entry entry, String password) {
            if (entry.bytes() > ChatStorageSizer.encapsulatedBytes(MAX_STEP_BYTES)) throw new ChatCryptoException("工具步骤超出安全读取范围");
            var read = cipher.read(entry.cipher(), entry.crypto(), password);
            if (read.status() != ChatMessageCipher.ReadStatus.DECRYPTED) throw new ChatCryptoException("工具步骤需要解锁或已损坏");
            try {
                var root = mapper.readTree(read.value());
                if (root.path("schemaVersion").asInt() != 1 || !entry.id().equals(root.path("entryId").asText()) || root.path("sessionId").asLong() != entry.sessionId()
                        || root.path("turnId").asLong() != entry.turnId() || !entry.requestId().equals(root.path("requestId").asText())
                        || root.path("revision").asLong() != entry.revision() || !fingerprint(read.value()).equals(entry.fingerprint()))
                    throw new ChatCryptoException("工具步骤来源身份已变化");
                List<Map<String, Object>> messages = mapper.convertValue(root.path("messages"), new TypeReference<>() { });
                validateClosed(messages);
                int offset = root.path("replyOffset").asInt(), characters = root.path("replyCharacters").asInt();
                if (offset < 0 || characters < 0) throw new IllegalArgumentException("回复位置无效");
                String boundary = root.path("memoryBoundary").isTextual() ? root.path("memoryBoundary").asText() : null;
                return new Step(entry, messages, offset, characters, boundary);
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException error) {
                throw new ChatCryptoException("工具步骤无法解析");
            }
    }

    static void validateClosed(List<Map<String, Object>> messages) {
        validateSequence(messages, true);
    }

    private static void validateSequence(List<Map<String, Object>> messages, boolean closed) {
        if (messages == null || messages.isEmpty() || messages.size() > 1024) throw new IllegalArgumentException("上下文步骤为空或超限");
        var calls = new java.util.LinkedHashSet<String>();
        var seen = new java.util.HashSet<String>();
        for (var message : messages) {
            if (message == null || !java.util.Set.of("assistant", "user", "tool").contains(String.valueOf(message.get("role"))))
                throw new IllegalArgumentException("上下文步骤角色无效");
            if (!calls.isEmpty() && !"tool".equals(message.get("role"))) throw new IllegalArgumentException("工具组不能被其它消息打断");
            if (message.get("tool_calls") instanceof List<?> items) for (var item : items) {
                if (!"assistant".equals(message.get("role")) || items.isEmpty() || items.size() > 128
                        || !(item instanceof Map<?, ?> call) || !(call.get("id") instanceof String id) || id.isBlank() || id.length() > 256 || !seen.add(id)
                        || !(call.get("function") instanceof Map<?, ?> function) || !(function.get("name") instanceof String name) || name.isBlank() || name.length() > 128
                        || !(function.get("arguments") instanceof String arguments) || arguments.length() > 4096)
                    throw new IllegalArgumentException("工具调用身份缺失或重复");
                try { if (!STEP_JSON.readTree(arguments).isObject()) throw new IllegalArgumentException("工具参数必须是对象"); }
                catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("工具参数未完整收到"); }
                calls.add(id);
            }
            if ("tool".equals(message.get("role")) && !calls.remove(message.get("tool_call_id")))
                throw new IllegalArgumentException("工具结果缺少配对调用");
        }
        if (closed && !calls.isEmpty()) throw new IllegalArgumentException("工具步骤尚未闭合");
    }

    static boolean memoryTool(String name) {
        return java.util.Set.of("search_memories", "list_memories", "create_memory", "update_memory", "delete_memory").contains(name);
    }

    static boolean usesMemory(List<Map<String, Object>> messages) {
        return messages.stream().anyMatch(message -> !Boolean.TRUE.equals(message.get(MEMORY_REDACTED_TAG))
                && message.get("tool_calls") instanceof List<?> calls && calls.stream().anyMatch(item -> item instanceof Map<?, ?> call
                && call.get("function") instanceof Map<?, ?> function && memoryTool(String.valueOf(function.get("name")))));
    }

    /** Keep call/result pairing and confirmed statuses while withdrawing the memory contents. */
    static List<Map<String, Object>> withoutMemoryContents(List<Map<String, Object>> messages) {
        var memoryCalls = new java.util.HashSet<String>();
        var result = new ArrayList<Map<String, Object>>();
        for (var message : messages) {
            var copy = new LinkedHashMap<>(message);
            if (message.get("tool_calls") instanceof List<?> calls) {
                memoryCalls.clear();
                var safeCalls = new ArrayList<Object>();
                boolean changed = false, onlyMemory = !calls.isEmpty();
                for (var item : calls) {
                    if (!(item instanceof Map<?, ?> call) || !(call.get("function") instanceof Map<?, ?> function)) throw new IllegalArgumentException("工具结构无效");
                    if (memoryTool(String.valueOf(function.get("name")))) {
                        memoryCalls.add(String.valueOf(call.get("id"))); changed = true;
                        safeCalls.add(Map.of("id", call.get("id"), "type", "function", "function", Map.of("name", function.get("name"), "arguments", "{}")));
                    } else { onlyMemory = false; safeCalls.add(item); }
                }
                if (changed) { copy.put("tool_calls", safeCalls); copy.put(MEMORY_REDACTED_TAG, true); copy.remove("anthropic_thinking"); copy.remove("reasoning_content"); }
                if (onlyMemory) copy.put("content", "");
            }
            if ("tool".equals(message.get("role")) && memoryCalls.contains(String.valueOf(message.get("tool_call_id")))) {
                String status = "UNAVAILABLE";
                try {
                    String original = STEP_JSON.readTree(String.valueOf(message.get("content"))).path("status").asText();
                    if (java.util.Set.of("CREATED", "UPDATED", "DELETED", "DUPLICATE", "NO_CHANGE").contains(original)) status = original;
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
                copy.put("content", "{\"status\":\"" + status + "\",\"reason\":\"MEMORY_CONTENT_WITHDRAWN\"}");
                copy.put(MEMORY_REDACTED_TAG, true);
            }
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private static final ObjectMapper STEP_JSON = new ObjectMapper();

    public static String fingerprint(String text) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
