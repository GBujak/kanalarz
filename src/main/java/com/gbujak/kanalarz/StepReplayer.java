package com.gbujak.kanalarz;

import com.gbujak.kanalarz.KanalarzPersistence.StepExecutedInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@NullMarked
class StepReplayer {

    private final SequencedMap<String, StepExecutedInfo> executionPathToStep;
    private final Set<String> replayed;

    private final KanalarzSerialization serialization;
    private final KanalarzStepsRegistry stepsRegistry;
    private volatile boolean poisoned = false;

    private final Lock lock = new ReentrantLock();

    public StepReplayer(
        List<StepExecutedInfo> steps,
        KanalarzSerialization serialization,
        KanalarzStepsRegistry stepsRegistry
    ) {
        this.serialization = serialization;
        this.stepsRegistry = stepsRegistry;

        this.executionPathToStep = new LinkedHashMap<>(steps.size());
        this.replayed = new HashSet<>(steps.size());

        Set<UUID> rolledBack =
            steps.stream()
                .flatMap(it -> it.wasRollbackFor().stream())
                .collect(Collectors.toSet());

        for (var step : steps) {
            if (step.wasRollbackFor().isEmpty() && !rolledBack.contains(step.stepId())) {
                executionPathToStep.put(step.executionPath(), step);
            }
        }
    }

    public SearchResult findNextStep(String executionPath, String stepIdentifier, String serializedParametersInfo) {
        if (poisoned) {
            throw new KanalarzException.KanalarzContextPoisonedException();
        }

        lock.lock();
        try {

            var step = executionPathToStep.get(executionPath);
            if (step == null) {
                throw new KanalarzException.KanalarzNewStepBeforeReplayEndedException(
                    "[%s] with execution path [%s] was called but following steps weren't yet replayed:\n%s"
                        .formatted(stepIdentifier, executionPath, unreplayedStepsInfo())
                );
            }
            if (!step.stepIdentifier().equals(stepIdentifier)) {
                throw new KanalarzException.KanalarzNewStepBeforeReplayEndedException(
                    ("[%s] with execution path [%s] was called but expected [%s] to be called at this position. " +
                        "Following steps weren't yet replayed:\n%s")
                        .formatted(stepIdentifier, executionPath, step.stepIdentifier(), unreplayedStepsInfo())
                );
            }
            if (!replayed.add(executionPath)) {
                throw new KanalarzException.KanalarzInternalError(
                    "StepReplayer has been called with the same path multiple times: [%s]. Steps left:\n%s"
                        .formatted(executionPath, unreplayedStepsInfo()), null
                );
            }

            if (!serialization.parametersAreEqualIgnoringReturn(
                step.serializedExecutionResult(),
                serializedParametersInfo
            )) {
                throw new KanalarzException.KanalarzNewStepBeforeReplayEndedException(
                    ("[%s] with execution path [%s] was called but different parameters than in the previous run. " +
                        "Expected [%s], but got [%s]").formatted(
                        stepIdentifier,
                        executionPath,
                        step.serializedExecutionResult(),
                        serializedParametersInfo
                    )
                );
            }

            if (step.failed()) {
                return SearchResult.FoundShouldRerun;
            }

            var stepInfo = stepsRegistry.getStepInfoOrThrow(step.stepIdentifier());

            var result = new SearchResult.Found(serialization.deserializeParameters(
                step.serializedExecutionResult(),
                Utils.makeDeserializeParamsInfo(stepInfo.paramsInfo),
                stepInfo.returnType
            ).executionResult());

            markChildrenAsReplayed(step.executionPath());

            return result;

        } catch (Throwable throwable) {
            poisoned = true;
            throw throwable;
        } finally {
            lock.unlock();
        }
    }

    public boolean isDone() {
        if (poisoned) {
            return false;
        }

        lock.lock();
        try {
            // Performance optimization:
            // the replayed set can only hold elements from the executionPathToStep keyset
            // therefore Set.equals can be replaced with a size equality check.
            return replayed.size() == executionPathToStep.size();
        } finally {
            lock.unlock();
        }
    }

    sealed interface SearchResult {
        record Found(@Nullable Object value) implements SearchResult {}
        record FoundShouldRerun() implements SearchResult {}
        SearchResult FoundShouldRerun = new FoundShouldRerun();
    }

    public String unreplayedStepsInfo() {
        return executionPathToStep.sequencedValues().stream()
            .filter(it -> !replayed.contains(it.executionPath()))
            .map(it -> "\t- [%s] with identifier [%s]".formatted(it.executionPath(), it.stepIdentifier()))
            .limit(10)
            .collect(Collectors.joining("\n")) +
            (executionPathToStep.size() > 10
                ? "\n\t and [" + (executionPathToStep.size() - 10) + "]more..."
                :  "");
    }

    private void markChildrenAsReplayed(String parentPath) {
        String prefix = parentPath + ".";
        executionPathToStep.keySet().stream()
            .filter(path -> path.startsWith(prefix))
            .forEach(replayed::add);
    }

    @Nullable
    String basePathForContextId(UUID contextId) {
        var basePathEnding = ".c-" + contextId;
        String result = null;
        boolean allNull = true;

        for (var path : executionPathToStep.keySet()) {
            var indexOf = path.indexOf(basePathEnding);
            if (indexOf == -1) {
                if (allNull) continue;
                throw new KanalarzException.KanalarzIllegalUsageException(
                    "Tried to resume-replay context [%s] but the list of steps had inconsistent root paths!"
                        .formatted(contextId)
                );
            }
            allNull = false;

            var basePath = path.substring(0, indexOf + basePathEnding.length());

            if (result != null && !result.equals(basePath)) {
                throw new KanalarzException.KanalarzIllegalUsageException(
                    "Tried to resume-replay context [%s] but the list of steps had inconsistent root paths!"
                        .formatted(contextId)
                );
            }

            result = basePath;
        }

        return result;
    }
}
