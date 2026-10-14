package io.github.springaiinsights.capture;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationView;
import org.jspecify.annotations.Nullable;

/** Finds the {@link TurnRecorder} of the turn an observation belongs to. */
public final class TurnRecorders {

    private TurnRecorders() {
    }

    /** Walks start → parents; returns the recorder stored on the OUTERMOST context that has one. */
    public static @Nullable TurnRecorder outermost(Observation.@Nullable ContextView start) {
        TurnRecorder found = null;
        Observation.ContextView current = start;
        while (current != null) {
            TurnRecorder recorder = current.get(TurnRecorder.class);
            if (recorder != null) {
                found = recorder;
            }
            ObservationView parent = current.getParentObservation();
            current = parent == null ? null : parent.getContextView();
        }
        return found;
    }

    public static void attach(Observation.Context context, TurnRecorder recorder) {
        context.put(TurnRecorder.class, recorder);
    }
}
