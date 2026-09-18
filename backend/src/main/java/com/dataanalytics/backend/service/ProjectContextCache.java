package com.dataanalytics.backend.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small in-process cache for rendered AI-assistant project contexts.
 * The 5-minute TTL is only a safety net: every state-changing operation
 * (upload/delete/clean/analyze/relationship changes) explicitly invalidates
 * the affected project's entry so answers never lag behind user actions.
 */
@Service
public class ProjectContextCache {

    private static final long TTL_MS = 5 * 60 * 1000L;

    private record Entry(String text, long createdAtMs) {
    }

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public String get(Long projectId, Supplier<String> loader) {
        Entry entry = cache.get(projectId);
        if (entry != null && System.currentTimeMillis() - entry.createdAtMs() < TTL_MS) {
            return entry.text();
        }
        String text = loader.get();
        cache.put(projectId, new Entry(text, System.currentTimeMillis()));
        return text;
    }

    public void invalidate(Long projectId) {
        cache.remove(projectId);
    }
}
