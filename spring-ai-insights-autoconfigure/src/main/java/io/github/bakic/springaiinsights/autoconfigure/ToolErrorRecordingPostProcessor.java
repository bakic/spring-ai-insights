package io.github.bakic.springaiinsights.autoconfigure;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Marks the tool observation as failed when a tool throws. Spring AI's default processor turns the exception into a
 * result the model reads, inside the tool observation, so the observation would otherwise end as a success. The
 * wrapped processor still decides what happens next (return a message or rethrow). Processors that can be subclassed
 * are proxied, so they keep their own type; others are wrapped behind the interface.
 */
class ToolErrorRecordingPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<ObservationRegistry> registry;

    ToolErrorRecordingPostProcessor(ObjectProvider<ObservationRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof ToolExecutionExceptionProcessor processor) || bean instanceof ErrorRecording
                || bean instanceof ErrorRecordingProxy) {
            return bean;
        }
        ErrorRecording recording = new ErrorRecording(processor, registry);
        if (!canSubclass(bean.getClass())) {
            return recording;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addInterface(ErrorRecordingProxy.class);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            Method method = invocation.getMethod();
            if (method.getName().equals("process") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == ToolExecutionException.class) {
                recording.recordError((ToolExecutionException) invocation.getArguments()[0]);
            }
            return invocation.proceed();
        });
        try {
            return proxy.getProxy(bean.getClass().getClassLoader());
        }
        catch (RuntimeException ex) {
            return recording;
        }
    }

    private static boolean canSubclass(Class<?> type) {
        if (Modifier.isFinal(type.getModifiers()) || type.isRecord() || type.isHidden()) {
            return false;
        }
        try {
            return !Modifier.isFinal(type.getMethod("process", ToolExecutionException.class).getModifiers());
        }
        catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /** Marks processors already proxied, so errors are recorded once. */
    interface ErrorRecordingProxy {

    }

    static final class ErrorRecording implements ToolExecutionExceptionProcessor {

        private final ToolExecutionExceptionProcessor delegate;

        private final ObjectProvider<ObservationRegistry> registry;

        ErrorRecording(ToolExecutionExceptionProcessor delegate, ObjectProvider<ObservationRegistry> registry) {
            this.delegate = delegate;
            this.registry = registry;
        }

        @Override
        public String process(ToolExecutionException exception) {
            recordError(exception);
            return delegate.process(exception);
        }

        void recordError(ToolExecutionException exception) {
            Observation current = registry.getIfUnique(() -> ObservationRegistry.NOOP).getCurrentObservation();
            if (current != null && current.getContext() instanceof ToolCallingObservationContext) {
                current.error(exception);
            }
        }
    }
}
