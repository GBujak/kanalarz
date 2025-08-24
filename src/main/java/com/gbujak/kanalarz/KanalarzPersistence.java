package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface KanalarzPersistence {

    record StepStartedEvent(
        @NonNull UUID contextId,
        @NonNull UUID stepId,
        @NonNull Optional<UUID> stepIsRollbackFor,
        @NonNull Map<String, String> metadata,
        @NonNull String stepIdentifier,
        boolean isFallible
    ) {}
    void stepStarted(StepStartedEvent stepStartedEvent);

    record StepCompletedEvent(
        @NonNull UUID contextId,
        @NonNull UUID stepId,
        @NonNull Map<String, String> metadata,
        @NonNull String stepIdentifier,
        @NonNull String serializedExecutionResult,
        boolean failed
    ) {}
    void stepCompleted(StepCompletedEvent stepCompletedEvent);

    record StepExecutedInfo(
        @NonNull UUID stepId,
        @NonNull String stepIdentifier,
        @NonNull String serializedExecutionResult,
        boolean failed
    ) {}
    @NonNull
    List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecution(@NonNull UUID contextId);
}
