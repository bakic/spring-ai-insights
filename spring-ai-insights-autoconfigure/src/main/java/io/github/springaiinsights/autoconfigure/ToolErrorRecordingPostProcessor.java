package io.github.springaiinsights.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Marks the tool observation as failed when a tool throws. Spring AI's default processor turns the exception into a
 * result the model reads, inside the tool observation, so the observation would otherwise end as a success. The
 * wrapped processor still decides what happens next (return a message or rethrow).
 */
class ToolErrorRecordingPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<ObservationRegistry> registry;

    ToolErrorRecordingPostProcessor(ObjectProvider<ObservationRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ToolExecutionExceptionProcessor processor && !(bean instanceof ErrorRecording)) {
            return new ErrorRecording(processor, registry);
        }
        return bean;
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
            Observation current = registry.getIfUnique(() -> ObservationRegistry.NOOP).getCurrentObservation();
            if (current != null && current.getContext() instanceof ToolCallingObservationContext) {
                current.error(exception);
            }
            return delegate.process(exception);
        }
    }
}
