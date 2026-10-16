package io.github.springaiinsights.capture;

import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.identity.InsightsIdentityResolver;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;

/**
 * Resolves who is calling when a ChatClient observation starts, on the caller's thread. Advisors run later: a streamed
 * request is subscribed first, and Reactor's automatic context propagation can clear thread locals such as the
 * {@code SecurityContext} at that moment when the caller did not capture them. Never throws: failures are counted in
 * {@code ai.insights.internal.errors}.
 */
public final class CallerIdentityCapture implements ObservationHandler<ChatClientObservationContext> {

    private final InsightsIdentityResolver identities;

    private final InsightsMetrics metrics;

    public CallerIdentityCapture(InsightsIdentityResolver identities, InsightsMetrics metrics) {
        this.identities = identities;
        this.metrics = metrics;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatClientObservationContext;
    }

    @Override
    public void onStart(ChatClientObservationContext context) {
        try {
            InsightsIdentity identity = identities.resolve(context.getRequest());
            if (identity != null) {
                context.put(InsightsIdentity.class, identity);
            }
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
    }

    static @Nullable InsightsIdentity captured(@Nullable ChatClientObservationContext context) {
        return context == null ? null : context.get(InsightsIdentity.class);
    }
}
