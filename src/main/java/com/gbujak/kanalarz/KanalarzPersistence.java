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
        UUID contextId,
        UUID stepId,
        Optional<UUID> parentStepId,
        Optional<UUID> stepIsRollbackFor,
        Map<String, String> metadata,
        String stepIdentifier,
        @Nullable ParameterizedStepDescription description,
        String serializedParameters,
        boolean isFallible,
        boolean isRollbackMarker
    ) {}
    void stepStarted(StepStartedEvent stepStartedEvent);

    record StepCompletedEvent(
        UUID contextId,
        UUID stepId,
        Optional<UUID> parentStepId,
        Optional<UUID> stepIsRollbackFor,
        Map<String, String> metadata,
        String stepIdentifier,
        @Nullable ParameterizedStepDescription description,
        String serializedExecutionResult,
        boolean failed,
        boolean isRollbackMarker
    ) {}
    void stepCompleted(StepCompletedEvent stepCompletedEvent);

    record StepExecutedInfo(
        UUID stepId,
        String stepIdentifier,
        String serializedExecutionResult,
        Optional<UUID> parentStepId,
        Optional<UUID> wasRollbackFor,
        boolean failed
    ) {}
    List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecution(UUID contextId);
}
