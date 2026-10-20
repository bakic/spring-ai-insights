package io.github.bakic.springaiinsights.identity;

import org.springframework.ai.chat.client.ChatClientRequest;

/** Resolves the user and tenant a chat request belongs to. */
@FunctionalInterface
public interface InsightsIdentityResolver {

    InsightsIdentity resolve(ChatClientRequest request);
}
