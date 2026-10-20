package io.github.bakic.springaiinsights.knowledge;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The documents a knowledge base holds, so the dashboard can show which of them are never retrieved. IDs have the
 * same form as the document IDs recorded for retrievals from {@link #store()}.
 */
public interface KnowledgeCorpusCatalog {

    /** The store name recorded on retrievals from this knowledge base. */
    String store();

    /** @return the documents, or {@code null} if they are not loaded yet */
    @Nullable Set<String> documentIds();
}
