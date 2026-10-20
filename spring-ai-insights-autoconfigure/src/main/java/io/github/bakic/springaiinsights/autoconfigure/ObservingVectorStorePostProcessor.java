package io.github.bakic.springaiinsights.autoconfigure;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.bakic.springaiinsights.knowledge.ObservedVectorStore;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Observes the searches of {@link VectorStore} beans that emit no observations. A store keeps its own type (a class
 * proxy intercepts its searches), so it can still be injected by its class; a {@code final} store class cannot be
 * subclassed and is wrapped in an {@link ObservedVectorStore}, so it must be injected as {@code VectorStore}.
 */
class ObservingVectorStorePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<ObservationRegistry> registry;

    ObservingVectorStorePostProcessor(ObjectProvider<ObservationRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof VectorStore store) || bean instanceof AbstractObservationVectorStore
                || bean instanceof ObservedVectorStore || bean instanceof ObservingProxy) {
            return bean;
        }
        ObservedVectorStore observed = new ObservedVectorStore(store, ObservedVectorStore.systemName(store),
                registry.getIfUnique(() -> ObservationRegistry.NOOP));
        if (!canSubclass(bean.getClass())) {
            return observed;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addInterface(ObservingProxy.class);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            Method method = invocation.getMethod();
            if (isSearch(method, SearchRequest.class)) {
                return observed.similaritySearch((SearchRequest) invocation.getArguments()[0]);
            }
            if (isSearch(method, String.class) && method.isDefault()) {
                return observed.similaritySearch((String) invocation.getArguments()[0]);
            }
            return invocation.proceed();
        });
        try {
            return proxy.getProxy(bean.getClass().getClassLoader());
        }
        catch (RuntimeException ex) {
            return observed;
        }
    }

    private static boolean isSearch(Method method, Class<?> parameter) {
        return method.getName().equals("similaritySearch") && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == parameter;
    }

    private static boolean canSubclass(Class<?> type) {
        if (Modifier.isFinal(type.getModifiers()) || type.isRecord() || type.isHidden()) {
            return false;
        }
        try {
            Method search = type.getMethod("similaritySearch", SearchRequest.class);
            return !Modifier.isFinal(search.getModifiers());
        }
        catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /** Marks stores already proxied, so a store is observed once. */
    interface ObservingProxy {

    }
}
