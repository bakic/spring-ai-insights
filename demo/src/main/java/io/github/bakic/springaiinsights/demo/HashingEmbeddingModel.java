package io.github.bakic.springaiinsights.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Offline, deterministic embedding: each content word is hashed into one of 256 buckets and the counts are
 * L2-normalised. Good enough for a small help center; no model provider is called.
 */
final class HashingEmbeddingModel extends AbstractEmbeddingModel {

    static final int DIMENSIONS = 256;

    private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "can", "do", "does", "for", "from",
            "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "our", "the", "to", "we", "what", "when",
            "where", "which", "will", "with", "you", "your", "be", "if", "at", "by", "this", "that", "get", "back");

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText() == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.isEmpty() || STOP_WORDS.contains(token)) {
                continue;
            }
            vector[Math.floorMod(stem(token).hashCode(), DIMENSIONS)] += 1f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            return vector;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    // "refunds" and "refund" land in the same bucket.
    private static String stem(String token) {
        return token.length() > 3 && token.endsWith("s") && !token.endsWith("ss") ? token.substring(0, token.length() - 1)
                : token;
    }
}
