package io.github.bakic.springaiinsights.autoconfigure;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.bakic.springaiinsights.identity.InsightsContextKeys;
import io.github.bakic.springaiinsights.identity.InsightsIdentity;
import io.github.bakic.springaiinsights.identity.InsightsIdentityResolver;

import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Reads the user and tenant from the request context ({@link InsightsContextKeys#USER_ID} and
 * {@link InsightsContextKeys#TENANT_ID}, set with {@code .advisors(a -> a.param(...))}). Used when Spring Security
 * is not on the classpath; requests without these keys are anonymous.
 */
public class ContextIdentityResolver implements InsightsIdentityResolver {

    @Override
    public InsightsIdentity resolve(ChatClientRequest request) {
        return new InsightsIdentity(contextValue(request, InsightsContextKeys.USER_ID),
                contextValue(request, InsightsContextKeys.TENANT_ID));
    }

    static @Nullable String contextValue(ChatClientRequest request, String key) {
        Map<String, @Nullable Object> context = request.context();
        Object value = context.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
