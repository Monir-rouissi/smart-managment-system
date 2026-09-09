package com.smartmgmt.management.search;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Bounded LRU of query embeddings.
 *
 * <p>The search box is debounced, but a user still retypes the same phrase and
 * every semantic request otherwise costs an embeddings call. Keyed by model as
 * well as by text: vectors from two models are not interchangeable, so a model
 * switch must miss rather than hand back the old vector.
 */
@Component
public class QueryEmbeddingCache {

    private static final int MAX_ENTRIES = 500;

    private final Map<String, float[]> entries = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized float[] get(String modelId, String query, Supplier<float[]> loader) {
        String key = modelId + ' ' + query.toLowerCase(Locale.ROOT);
        float[] cached = entries.get(key);
        if (cached != null) {
            return cached;
        }
        float[] vector = loader.get();
        entries.put(key, vector);
        return vector;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
