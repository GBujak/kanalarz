package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@NullMarked
sealed interface KanalarzStepReplayer {

    final class KanalarzInOrderStepReplayer implements KanalarzStepReplayer {

        private final KanalarzSerialization serialization;
        private final KanalarzStepsRegistry stepsRegistry;
        private final List<KanalarzPersistence.StepExecutedInfo> stepsToReplay;
        private int currentStep = 0;
        private final ReentrantLock lock = new ReentrantLock();

        KanalarzInOrderStepReplayer(
            KanalarzSerialization serialization,
            KanalarzStepsRegistry stepsRegistry,
            List<KanalarzPersistence.StepExecutedInfo> stepsExecuted
        ) {
            this.serialization = serialization;
            this.stepsRegistry = stepsRegistry;
            this.stepsToReplay = buildStepsToReplay(stepsExecuted);
        }

        @Override
        public SearchResult findNextStep(String stepIdentifier, String serializedParametersInfo) {
            try {
                lock.lock();

                if (isDone()) {
                    throw new KanalarzException.KanalarzInternalError(
                        "In order step replayer called after it ran out of steps",
                        null
                    );
                }

                var executedStep = stepsToReplay.get(currentStep);
                if (!stepIdentifier.equals(executedStep.stepIdentifier())) {
                    return new SearchResult.NotFound();
                }

                if (!serialization.parametersAreEqualIgnoringReturn(
                    executedStep.serializedExecutionResult(),
                    serializedParametersInfo
                )) {
                    return SearchResult.NotFound;
                }

                currentStep++;
                var executedStepInfo = stepsRegistry.getStepInfoOrThrow(executedStep.stepIdentifier());

                if (executedStep.failed()) {
                    return SearchResult.FoundShouldRerun;
                } else {
                    return new SearchResult.Found(serialization.deserializeParameters(
                        executedStep.serializedExecutionResult(),
                        Utils.makeDeserializeParamsInfo(executedStepInfo.paramsInfo),
                        executedStepInfo.returnType
                    ).executionResult());
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isDone() {
            try {
                lock.lock();
                return currentStep >= stepsToReplay.size();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<KanalarzPersistence.StepExecutedInfo> unreplayed() {
            try {
                lock.lock();
                return stepsToReplay.stream().skip(currentStep).toList();
            } finally {
                lock.unlock();
            }
        }
    }

    final class KanalarzOutOfOrderStepReplayer implements KanalarzStepReplayer {

        private final KanalarzSerialization serialization;
        private final KanalarzStepsRegistry stepsRegistry;
        private final List<KanalarzPersistence.StepExecutedInfo> stepsToReplay;
        private final ReentrantLock lock = new ReentrantLock();

        private final boolean[] stepsReplayed;
        private int firstUnreplayedIndex = 0;

        KanalarzOutOfOrderStepReplayer(
            KanalarzSerialization serialization,
            KanalarzStepsRegistry stepsRegistry,
            List<KanalarzPersistence.StepExecutedInfo> stepsExecuted
        ) {
            this.serialization = serialization;
            this.stepsRegistry = stepsRegistry;
            this.stepsToReplay = KanalarzStepReplayer.buildStepsToReplay(stepsExecuted);
            this.stepsReplayed = new boolean[this.stepsToReplay.size()];
        }

        @Override
        public SearchResult findNextStep(String stepIdentifier, String serializedParametersInfo) {
            try {
                lock.lock();

                boolean encounteredUnreplayed = false;
                for (int i = firstUnreplayedIndex; i < stepsToReplay.size(); i++) {
                    if (stepsReplayed[i]) {
                        if (!encounteredUnreplayed) {
                            firstUnreplayedIndex = i + 1;
                        }
                        continue;
                    }
                    encounteredUnreplayed = true;

                    var executedStep = stepsToReplay.get(i);
                    if (!stepIdentifier.equals(executedStep.stepIdentifier())) {
                        continue;
                    }

                    if (!serialization.parametersAreEqualIgnoringReturn(
                        executedStep.serializedExecutionResult(),
                        serializedParametersInfo
                    )) {
                        continue;
                    }

                    stepsReplayed[i] = true;
                    if (firstUnreplayedIndex == i) {
                        while (
                            firstUnreplayedIndex < stepsReplayed.length &&
                                stepsReplayed[firstUnreplayedIndex]
                        ) {
                            firstUnreplayedIndex++;
                        }
                    }

                    var executedStepInfo = stepsRegistry.getStepInfoOrThrow(executedStep.stepIdentifier());

                    if (executedStep.failed()) {
                        return SearchResult.FoundShouldRerun;
                    } else {
                        return new SearchResult.Found(serialization.deserializeParameters(
                            executedStep.serializedExecutionResult(),
                            Utils.makeDeserializeParamsInfo(executedStepInfo.paramsInfo),
                            executedStepInfo.returnType
                        ).executionResult());
                    }
                }
                return SearchResult.NotFound;

            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isDone() {
            try {
                lock.lock();
                return firstUnreplayedIndex == stepsToReplay.size();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<KanalarzPersistence.StepExecutedInfo> unreplayed() {
            try {
                lock.lock();
                List<KanalarzPersistence.StepExecutedInfo> result = new ArrayList<>();
                for (int i = firstUnreplayedIndex; i < stepsToReplay.size(); i++) {
                    if (stepsReplayed[i]) {
                        result.add(stepsToReplay.get(i));
                    }
                }
                return Collections.unmodifiableList(result);
            } finally {
                lock.unlock();
            }
        }
    }

    SearchResult findNextStep(String stepIdentifier, String serializedParametersInfo);
    boolean isDone();
    List<KanalarzPersistence.StepExecutedInfo> unreplayed();

    static private List<KanalarzPersistence.StepExecutedInfo>
    buildStepsToReplay(List<KanalarzPersistence.StepExecutedInfo> stepsExecuted) {
        List<KanalarzPersistence.StepExecutedInfo> result = new ArrayList<>();
        HashSet<UUID> parentsProcessed = new HashSet<>();

        stepLoop: for (int i = 0; i < stepsExecuted.size(); i++) {
            var step = stepsExecuted.get(i);

            if (step.failed()) {
                continue;
            }

            var parentId = step.parentStepId().orElse(null);
            if (parentId == null || parentsProcessed.contains(parentId)) {
                result.add(step);
                continue;
            }

            parentLoop: for (int j = i + 1; j < stepsExecuted.size(); j++) {
                var parent = stepsExecuted.get(j);
                if (!parent.stepId().equals(parentId)) {
                    continue parentLoop;
                }

                if (!parent.failed()) {
                    continue stepLoop;
                }

                parentsProcessed.add(parentId);
                result.add(parent);
                result.add(step);
            }
        }

        return result;
    }

    sealed interface SearchResult {
        record Found(@Nullable Object value) implements SearchResult {}
        record NotFound() implements SearchResult {}
        record FoundShouldRerun() implements SearchResult {}

        SearchResult NotFound = new NotFound();
        SearchResult FoundShouldRerun = new FoundShouldRerun();
    }
}
