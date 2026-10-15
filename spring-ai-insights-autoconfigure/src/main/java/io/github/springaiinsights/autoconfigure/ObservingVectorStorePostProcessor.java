package io.github.springaiinsights.autoconfigure;

import io.github.springaiinsights.knowledge.ObservedVectorStore;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Wraps {@link VectorStore} beans that emit no observations in an {@link ObservedVectorStore}. */
class ObservingVectorStorePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<ObservationRegistry> registry;

    ObservingVectorStorePostProcessor(ObjectProvider<ObservationRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof VectorStore store && !(bean instanceof AbstractObservationVectorStore)
                && !(bean instanceof ObservedVectorStore)) {
            return new ObservedVectorStore(store, ObservedVectorStore.systemName(store),
                    registry.getIfUnique(() -> ObservationRegistry.NOOP));
        }
        return bean;
    }
}
