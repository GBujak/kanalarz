package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.jetbrains.annotations.NotNull;
import org.springframework.lang.NonNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.gbujak.kanalarz.Utils.isStepOut;

class KanalarzStepsRegistry {

    private final Map<String, StepInfoClasses.StepInfo> steps = new HashMap<>();
    private final Map<String, String> rollbackStepsForRollforwardSteps = new HashMap<>();


    synchronized void registerRollforwardStep(
        Object target,
        Method method,
        StepsHolder stepsHolder,
        Step step,
        boolean returnIsSecret
    ) {
        String stepIdentifier = stepIdentifier(stepsHolder, step);
        if (this.steps.containsKey(stepIdentifier)) {
            throw new RuntimeException("Duplicated step identifier %s".formatted(stepIdentifier));
        }
        var stepInfo = StepInfoClasses.StepInfo.createNew(target, method, step, returnIsSecret);
        if (step.fallible() && !isStepOut(stepInfo.returnType)) {
            throw new RuntimeException(
                "Fallible steps must return a [%s] instance so the error can be wrapped and returned."
                    .formatted(StepOut.class.getCanonicalName())
            );
        }
        steps.put(stepIdentifier, stepInfo);
    }

    synchronized void registerRollbackStep(
        Object target,
        Method method,
        StepsHolder stepsHolder,
        Rollback rollback,
        boolean returnIsSecret
    ) {
        var stepIdentifier = stepIdentifier(stepsHolder, rollback);
        var rollbackIdentifier = rollbackIdentifier(stepsHolder, rollback);
        if (this.steps.containsKey(rollbackIdentifier)) {
            throw new RuntimeException("Duplicated step identifier %s".formatted(stepIdentifier));
        }

        StepInfoClasses.StepInfo rollforwardStep = steps.get(stepIdentifier);
        if (rollforwardStep == null) {
            throw new RuntimeException(
                "Could not find step [%s] which is the rollforward step of a rollback step [%s] being processed"
                    .formatted(stepIdentifier, rollback)
            );
        }

        var stepInfo = StepInfoClasses.StepInfo.createNew(target, method, rollback, returnIsSecret);

        for (var param : stepInfo.paramsInfo) {
            if (param.isRollforwardOutput) {
                var expectedType =
                    Utils.isStepOut(rollforwardStep.returnType)
                        ? Utils.getTypeFromStepOut(rollforwardStep.returnType)
                        : rollforwardStep.returnType;
                if (!param.type.equals(expectedType)) {
                    throw new RuntimeException(
                        ("Rollback step [%s] declares a rollforward step [%s] output parameter [%s] but the return " +
                            "type of the rollforward step and that parameter don't match! (rollback param is [%s] " +
                            "and rollforward param is [%s]). Expected rollback param to be [%s]")
                            .formatted(
                                rollbackIdentifier,
                                stepIdentifier,
                                param.paramName,
                                param.type.getTypeName(),
                                rollforwardStep.returnType.getTypeName(),
                                expectedType.getTypeName()
                            )
                    );
                }
                if (param.isNonNullable != rollforwardStep.isReturnTypeNonNullable) {
                    throw new RuntimeException(
                        ("Rollback step [%s] declares a rollforward step [%s] output parameter [%s] but the return " +
                            "type of the rollforward step and that parameter have different nullability markings!")
                            .formatted(
                                rollbackIdentifier,
                                stepIdentifier,
                                param.paramName
                            )
                    );
                }
                continue;
            }

            Optional<StepInfoClasses.ParamInfo> correspondingParam =
                rollforwardStep.paramsInfo.stream()
                    .filter(it -> it.paramName.equals(param.paramName))
                    .findAny();

            if (correspondingParam.isEmpty()) {
                throw new RuntimeException(
                    "Could not find corresponding param for param [%s] in step [%s] when processing rollback step [%s]"
                        .formatted(param.paramName, stepIdentifier, rollbackIdentifier)
                );
            }

            if (!correspondingParam.get().type.equals(param.type)) {
                throw new RuntimeException(
                    ("Rollback step [%s] declares a parameter [%s] from the rollforward step [%s] " +
                        "but the types are different! ([%s] and [%s])")
                        .formatted(
                            rollbackIdentifier,
                            param.paramName,
                            stepIdentifier,
                            param.type.getTypeName(),
                            correspondingParam.get().type.getTypeName()
                        )
                );
            }
        }

        steps.put(rollbackIdentifier, stepInfo);
        rollbackStepsForRollforwardSteps.put(stepIdentifier, rollbackIdentifier);
    }

    @NonNull
    StepInfoClasses.StepInfo getStepInfo(StepsHolder stepsHolder, Step step) {
        var identifier = stepIdentifier(stepsHolder, step);
        return getStepInfoOrThrow(identifier);
    }

    @NonNull
    Optional<StepInfoClasses.StepInfo> getStepRollbackInfo(StepsHolder stepsHolder, Step step) {
        return Optional.of(stepIdentifier(stepsHolder, step))
            .map(this.rollbackStepsForRollforwardSteps::get)
            .map(this::getStepInfoOrThrow);
    }

    private StepInfoClasses.StepInfo getStepInfoOrThrow(String identifier) {
        return Optional.ofNullable(this.steps.get(identifier))
            .orElseThrow(() -> new RuntimeException(
                "Could not find step for identifier [%s]".formatted(identifier))
            );
    }

    @NotNull
    private static String stepIdentifier(StepsHolder stepsHolder, Step step) {
        return "%s:%s".formatted(stepsHolder.identifier(), step.identifier());
    }

    @NotNull
    private static String stepIdentifier(StepsHolder stepsHolder, Rollback rollback) {
        return "%s:%s".formatted(stepsHolder.identifier(), rollback.forStep());
    }

    @NotNull
    private static String rollbackIdentifier(StepsHolder stepsHolder, Rollback rollback) {
        return "%s:rollback".formatted(stepIdentifier(stepsHolder, rollback));
    }
}
