package com.smartmgmt.management.document.ingest;

import java.util.List;

/**
 * Turns chunk text into vectors. Kept as a narrow interface with an offline
 * implementation so the whole pipeline -- including its tests -- runs without an
 * API key or network access.
 */
public interface EmbeddingClient {

    /** Embeds every text, in order. Implementations batch internally. */
    List<float[]> embed(List<String> texts);

    /** Stored on the document so a later model switch is detectable. */
    String modelId();

    int dimensions();
}
