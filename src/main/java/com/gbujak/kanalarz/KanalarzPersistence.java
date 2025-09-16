package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface KanalarzPersistence<T> {

    record StepStartedEvent(
        @NonNull UUID contextId,
        @NonNull UUID stepId,
        @NonNull Optional<UUID> parentStepId,
        @NonNull Optional<UUID> stepIsRollbackFor,
        @NonNull Map<String, String> metadata,
        @NonNull String stepIdentifier,
        @Nullable String description,
        boolean isFallible
    ) {}
    void stepStarted(StepStartedEvent stepStartedEvent);

    record StepCompletedEvent<T>(
        @NonNull UUID contextId,
        @NonNull UUID stepId,
        @NonNull Optional<UUID> parentStepId,
        @NonNull Optional<UUID> stepIsRollbackFor,
        @NonNull Map<String, String> metadata,
        @NonNull String stepIdentifier,
        @Nullable String description,
        @NonNull T serializedExecutionResult,
        boolean failed
    ) {}
    void stepCompleted(StepCompletedEvent<T> stepCompletedEvent);

    record StepExecutedInfo<T>(
        @NonNull UUID stepId,
        @NonNull String stepIdentifier,
        @NonNull T serializedExecutionResult,
        @NonNull Optional<UUID> parentStepId,
        @NonNull Optional<UUID> wasRollbackFor,
        boolean failed
    ) {}
    @NonNull
    List<StepExecutedInfo<T>> getExecutedStepsInContextInOrderOfExecution(@NonNull UUID contextId);
}
