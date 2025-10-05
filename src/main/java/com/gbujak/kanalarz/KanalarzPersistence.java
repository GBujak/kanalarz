package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface KanalarzPersistence {

    record StepStartedEvent(
        @NonNull UUID contextId,
        @NonNull UUID stepId,
        @NonNull Optional<UUID> parentStepId,
        @NonNull Optional<UUID> stepIsRollbackFor,
        @NonNull Map<String, String> metadata,
        @NonNull String stepIdentifier,
        @Nullable ParameterizedStepDescription description,
        @NonNull String serializedParameters,
        boolean isFallible
    ) {}
    void stepStarted(@NonNull StepStartedEvent stepStartedEvent);

    record StepCompletedEvent(
        @NonNull UUID contextId,
        @NonNull UUID stepId,
        @NonNull Optional<UUID> parentStepId,
        @NonNull Optional<UUID> stepIsRollbackFor,
        @NonNull Map<String, String> metadata,
        @NonNull String stepIdentifier,
        @Nullable ParameterizedStepDescription description,
        @NonNull String serializedExecutionResult,
        boolean failed
    ) {}
    void stepCompleted(@NonNull StepCompletedEvent stepCompletedEvent);

    record StepExecutedInfo(
        @NonNull UUID stepId,
        @NonNull String stepIdentifier,
        @NonNull String serializedExecutionResult,
        @NonNull Optional<UUID> parentStepId,
        @NonNull Optional<UUID> wasRollbackFor,
        boolean failed
    ) {}
    @NonNull
    List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecution(@NonNull UUID contextId);
}
