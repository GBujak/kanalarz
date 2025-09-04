package com.gbujak.kanalarz;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

sealed interface KanalarzStepReplayer {

    final class KanalarzInOrderStepReplayer implements KanalarzStepReplayer {

        private static final Object noReturnValueMarker = new Object();

        private final KanalarzSerialization serialization;
        private final KanalarzStepsRegistry stepsRegistry;
        private final List<KanalarzPersistence.StepExecutedInfo> stepsToReplay;
        private Object nextStepReturnValue = noReturnValueMarker;
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
                    return SearchResult.NOT_FOUND;
                }

                if (!serialization.parametersAreEqualIgnoringReturn(
                    executedStep.serializedExecutionResult(),
                    serializedParametersInfo
                )) {
                    return SearchResult.NOT_FOUND;
                }

                currentStep++;
                var executedStepInfo = stepsRegistry.getStepInfoOrThrow(executedStep.stepIdentifier());

                if (executedStep.failed()) {
                    return SearchResult.FOUND_SHOULD_RERUN;
                } else {
                    nextStepReturnValue = serialization.deserializeParameters(
                        executedStep.serializedExecutionResult(),
                        Utils.makeDeserializeParamsInfo(executedStepInfo.paramsInfo),
                        executedStepInfo.returnType
                    ).executionResult();
                    return SearchResult.FOUND;
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Object getNextStepReturnValue() {
            try {
                lock.lock();
                var tmp = nextStepReturnValue;
                if (tmp == noReturnValueMarker) {
                    throw new KanalarzException.KanalarzInternalError(
                        "Tried to get return value from in order step replayer twice or before finding next step",
                        null
                    );
                }
                nextStepReturnValue = noReturnValueMarker;
                return tmp;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isDone() {
            return currentStep >= stepsToReplay.size();
        }

        @Override
        public List<KanalarzPersistence.StepExecutedInfo> unreplayed() {
            return stepsToReplay.stream().skip(currentStep).toList();
        }
    }

    final class KanalarzOutOfOrderStepReplayer implements KanalarzStepReplayer {

        private static final Object noReturnValueMarker = new Object();

        private final KanalarzSerialization serialization;
        private final KanalarzStepsRegistry stepsRegistry;
        private final List<KanalarzPersistence.StepExecutedInfo> stepsToReplay;
        private Object nextStepReturnValue = noReturnValueMarker;
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
                        firstUnreplayedIndex++;
                    }

                    var executedStepInfo = stepsRegistry.getStepInfoOrThrow(executedStep.stepIdentifier());

                    if (executedStep.failed()) {
                        return SearchResult.FOUND_SHOULD_RERUN;
                    } else {
                        nextStepReturnValue = serialization.deserializeParameters(
                            executedStep.serializedExecutionResult(),
                            Utils.makeDeserializeParamsInfo(executedStepInfo.paramsInfo),
                            executedStepInfo.returnType
                        ).executionResult();
                        return SearchResult.FOUND;
                    }
                }
                return SearchResult.NOT_FOUND;

            } finally {
                lock.unlock();
            }
        }

        @Override
        public Object getNextStepReturnValue() {
            try {
                lock.lock();
                var tmp = nextStepReturnValue;
                if (tmp == noReturnValueMarker) {
                    throw new KanalarzException.KanalarzInternalError(
                        "Tried to get return value from out of order step replayer twice or before finding next step",
                        null
                    );
                }
                nextStepReturnValue = noReturnValueMarker;
                return tmp;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isDone() {
            return firstUnreplayedIndex == stepsToReplay.size();
        }

        @Override
        public List<KanalarzPersistence.StepExecutedInfo> unreplayed() {
            List<KanalarzPersistence.StepExecutedInfo> result = new ArrayList<>();
            for (int i = firstUnreplayedIndex; i < stepsToReplay.size(); i++) {
                if (stepsReplayed[i]) {
                    result.add(stepsToReplay.get(i));
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    SearchResult findNextStep(String stepIdentifier, String serializedParametersInfo);
    Object getNextStepReturnValue();
    boolean isDone();
    List<KanalarzPersistence.StepExecutedInfo> unreplayed();

    enum SearchResult {
        FOUND,
        NOT_FOUND,
        FOUND_SHOULD_RERUN
    }


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
}
