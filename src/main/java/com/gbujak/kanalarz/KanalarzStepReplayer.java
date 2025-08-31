package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

sealed interface KanalarzStepReplayer {

    final class KanalarzInOrderStepReplayer implements KanalarzStepReplayer {

        private static final Object noReturnValueMarker = new Object();

        private final KanalarzSerialization serialization;
        private final KanalarzStepsRegistry stepsRegistry;
        private final List<KanalarzPersistence.StepExecutedInfo> stepsExecuted;
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
            this.stepsExecuted = stepsExecuted.stream().filter(it -> !it.failed()).toList();
        }

        @Override
        public boolean findNextStep(String stepIdentifier, String serializedParametersInfo) {
            try {
                lock.lock();

                if (isDone()) {
                    throw new KanalarzException.KanalarzInternalError(
                        "In order step replayer called after it ran out of steps",
                        null
                    );
                }

                var executedStep = stepsExecuted.get(currentStep);
                if (!stepIdentifier.equals(executedStep.stepIdentifier())) {
                    return false;
                }

                if (!serialization.parametersAreEqualIgnoringReturn(
                    executedStep.serializedExecutionResult(),
                    serializedParametersInfo
                )) {
                    return false;
                }

                var executedStepInfo = stepsRegistry.getStepInfoOrThrow(executedStep.stepIdentifier());
                nextStepReturnValue = serialization.deserializeParameters(
                    executedStep.serializedExecutionResult(),
                    Utils.makeDeserializeParamsInfo(executedStepInfo.paramsInfo),
                    executedStepInfo.returnType
                ).executionResult();

                currentStep++;
                return true;

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
            return currentStep >= stepsExecuted.size();
        }

        @Override
        public List<KanalarzPersistence.StepExecutedInfo> unreplayed() {
            return stepsExecuted.stream().skip(currentStep).toList();
        }
    }

    final class KanalarzOutOfOrderStepReplayer implements KanalarzStepReplayer {

        private static final Object noReturnValueMarker = new Object();

        private final KanalarzSerialization serialization;
        private final KanalarzStepsRegistry stepsRegistry;
        private final List<KanalarzPersistence.StepExecutedInfo> stepsExecuted;
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
            this.stepsExecuted = stepsExecuted.stream().filter(it -> !it.failed()).toList();
            this.stepsReplayed = new boolean[this.stepsExecuted.size()];
        }

        @Override
        public boolean findNextStep(String stepIdentifier, String serializedParametersInfo) {
            try {
                lock.lock();

                boolean encounteredUnreplayed = false;
                for (int i = firstUnreplayedIndex; i < stepsExecuted.size(); i++) {
                    if (stepsReplayed[i]) {
                        if (!encounteredUnreplayed) {
                            firstUnreplayedIndex = i + 1;
                        }
                        continue;
                    }
                    encounteredUnreplayed = true;

                    var executedStep = stepsExecuted.get(i);
                    if (!stepIdentifier.equals(executedStep.stepIdentifier())) {
                        continue;
                    }

                    if (!serialization.parametersAreEqualIgnoringReturn(
                        executedStep.serializedExecutionResult(),
                        serializedParametersInfo
                    )) {
                        continue;
                    }

                    var executedStepInfo = stepsRegistry.getStepInfoOrThrow(executedStep.stepIdentifier());
                    nextStepReturnValue = serialization.deserializeParameters(
                        executedStep.serializedExecutionResult(),
                        Utils.makeDeserializeParamsInfo(executedStepInfo.paramsInfo),
                        executedStepInfo.returnType
                    ).executionResult();

                    stepsReplayed[i] = true;
                    return true;
                }
                return false;

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
            return firstUnreplayedIndex == stepsExecuted.size();
        }

        @Override
        public List<KanalarzPersistence.StepExecutedInfo> unreplayed() {
            List<KanalarzPersistence.StepExecutedInfo> result = new ArrayList<>();
            for (int i = firstUnreplayedIndex; i < stepsExecuted.size(); i++) {
                if (stepsReplayed[i]) {
                    result.add(stepsExecuted.get(i));
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    boolean findNextStep(String stepIdentifier, String serializedParametersInfo);
    Object getNextStepReturnValue();
    boolean isDone();
    List<KanalarzPersistence.StepExecutedInfo> unreplayed();
}
