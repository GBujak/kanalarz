package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@NullMarked
public interface KanalarzPersistence {

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
        int threadId
    ) {}
    void stepStarted(StepStartedEvent stepStartedEvent);

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
        int threadId
    ) {}
    void stepCompleted(StepCompletedEvent stepCompletedEvent);

    record StepExecutedInfo(
        List<UUID> contexts,
        UUID stepId,
        String stepIdentifier,
        String serializedExecutionResult,
        Optional<UUID> parentStepId,
        Optional<UUID> wasRollbackFor,
        boolean failed,
        int threadId
    ) {}

    /**
     * Get a list of executed steps in the context with the given id, or within any nested contexts indide of that one.
     * Sorted by the time the execution was completed, ascendingly.
     */
    List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecution(UUID contextId);
}
