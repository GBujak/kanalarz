package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@NullMarked
public interface KanalarzPersistence {

    /**
     * @param contexts The context stack in which this step was executed.
     *                 Start of the list is the top-level context, end is the bottom
     * @param forkJoinIdx Index into the list passed into a forkJoin call if this step is being executed in a forkJoin,
     *                    else <code>-1</code>. This allows the forkJoins to be replayed in order. Can be used to
     *                    visualize parallel execution to the users.
     */
    record StepStartedEvent(
        List<UUID> contexts,
        UUID stepId,
        Optional<UUID> parentStepId,
        Optional<UUID> stepIsRollbackFor,
        Map<String, String> metadata,
        String stepIdentifier,
        @Nullable ParameterizedStepDescription description,
        String serializedParameters,
        boolean isFallible,
        boolean isRollbackMarker,
        int forkJoinIdx
    ) {}
    void stepStarted(StepStartedEvent stepStartedEvent);

    /**
     * @param contexts The context stack in which this step was executed.
     *                 Start of the list is the top-level context, end is the bottom
     * @param forkJoinIdx Index into the list passed into a forkJoin call if this step is being executed in a forkJoin,
     *                    else <code>-1</code>. This allows the forkJoins to be replayed in order. Can be used to
     *                    visualize parallel execution to the users.
     */
    record StepCompletedEvent(
        List<UUID> contexts,
        UUID stepId,
        Optional<UUID> parentStepId,
        Optional<UUID> stepIsRollbackFor,
        Map<String, String> metadata,
        String stepIdentifier,
        @Nullable ParameterizedStepDescription description,
        String serializedExecutionResult,
        boolean failed,
        boolean isRollbackMarker,
        int forkJoinIdx
    ) {}
    void stepCompleted(StepCompletedEvent stepCompletedEvent);

    /**
     * @param contexts The context stack in which this step was executed.
     *                 Start of the list is the top-level context, end is the bottom
     * @param forkJoinIdx Index into the list passed into a forkJoin call if this step is being executed in a forkJoin,
     *                    else <code>-1</code>. This allows the forkJoins to be replayed in order. Can be used to
     *                    visualize parallel execution to the users.
     */
    record StepExecutedInfo(
        List<UUID> contexts,
        UUID stepId,
        String stepIdentifier,
        String serializedExecutionResult,
        Optional<UUID> parentStepId,
        Optional<UUID> wasRollbackFor,
        boolean failed,
        int forkJoinIdx
    ) {}

    /**
     * Get a list of executed steps in the context with the given id, or within any nested contexts inside of that one.
     * Sorted by the time the execution was completed, ascendingly.
     */
    List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecution(UUID contextId);
}
