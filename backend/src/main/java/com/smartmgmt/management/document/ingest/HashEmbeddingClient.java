package com.smartmgmt.management.document.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic offline embeddings: SHA-256 of the text seeds a PRNG that fills
 * a unit vector. Same text in, same vector out, no network.
 *
 * <p>This exists so the pipeline can be run and tested end to end without an
 * OpenAI key. The vectors carry no semantics, so similarity search over them is
 * meaningless -- it is a stand-in for the transport, not for the model.
 */
public class HashEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(HashEmbeddingClient.class);

    private final int dimensions;

    public HashEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
        log.warn("No embeddings API key configured (app.ingest.openai.api-key) -- using deterministic "
                + "offline embeddings. Chunks will be stored, but semantic search over them is meaningless.");
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorFor(text));
        }
        return vectors;
    }

    private float[] vectorFor(String text) {
        Random random = new Random(seed(text));
        float[] vector = new float[dimensions];
        double norm = 0;
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += (double) vector[i] * vector[i];
        }
        float length = (float) Math.sqrt(norm);
        if (length > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= length;
            }
        }
        return vector;
    }

    private long seed(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (digest[i] & 0xffL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    @Override
    public String modelId() {
        return "offline-hash-" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
