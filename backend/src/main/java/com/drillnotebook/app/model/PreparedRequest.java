package com.drillnotebook.app.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final protocol payload, frozen before counting and sending; credentials live only in the transport target. */
public record PreparedRequest(Map<String, Object> body, String serialized, RequestContextAudit context, boolean stream) {
    public PreparedRequest { body = freeze(body); }
    @SuppressWarnings("unchecked")
    private static <T> T freeze(T value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), freeze(item)));
            return (T) Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return (T) list.stream().map(PreparedRequest::freeze).toList();
        return value;
    }
}
