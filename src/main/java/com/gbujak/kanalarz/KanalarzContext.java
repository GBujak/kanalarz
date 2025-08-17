package com.gbujak.kanalarz;

import com.gbujak.kanalarz.StepInfoClasses.ParamInfo;
import com.gbujak.kanalarz.StepInfoClasses.StepInfo;
import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class KanalarzContext {

    private final Map<String, StepInfo> steps = new HashMap<>();
    private final Map<String, String> rollbackStepsForRollforwardSteps = new HashMap<>();

    Object handleMethodInvocation(
        Object target,
        MethodInvocation invocation,
        StepsHolder stepsHolder,
        Step step
    ) throws InvocationTargetException, IllegalAccessException {
        var method = invocation.getMethod();
        var arguments = invocation.getArguments();
        return method.invoke(target, arguments);
    }

    synchronized void registerRollforwardStep(
        Object target,
        Method method,
        StepsHolder stepsHolder,
        Step step
    ) {
        String stepIdentifier = "%s:%s".formatted(stepsHolder.identifier(), step.identifier());
        if (this.steps.containsKey(stepIdentifier)) {
            throw new RuntimeException("Duplicated step identifier %s".formatted(stepIdentifier));
        }
        var stepInfo = StepInfo.createNew(target, method, step);
        steps.put(stepIdentifier, stepInfo);
    }

    synchronized void registerRollbackStep(
        Object target,
        Method method,
        StepsHolder stepsHolder,
        Rollback rollback
    ) {
        var stepIdentifier = "%s:%s".formatted(stepsHolder.identifier(), rollback.forStep());
        var rollbackIdentifier = "%s:rollback".formatted(stepIdentifier);
        if (this.steps.containsKey(rollbackIdentifier)) {
            throw new RuntimeException("Duplicated step identifier %s".formatted(stepIdentifier));
        }

        StepInfo rollforwardStep = steps.get(stepIdentifier);
        if (rollforwardStep == null) {
            throw new RuntimeException(
                "Could not find step [%s] which is the rollforward step of a rollback step [%s] being processed"
                    .formatted(stepIdentifier, rollback)
            );
        }

        var stepInfo = StepInfo.createNew(target, method, rollback);

        for (var param : stepInfo.paramsInfo) {
            if (param.isRollforwardOutput) {
                if (!param.type.equals(rollforwardStep.returnType)) {
                    throw new RuntimeException(
                        ("Rollback step [%s] declares a rollforward step [%s] output parameter [%s] but the return " +
                            "type of the rollforward step and that parameter are different! ([%s] and [%s])")
                            .formatted(
                                rollbackIdentifier,
                                stepIdentifier,
                                param.paramName,
                                param.type.getTypeName(),
                                rollforwardStep.returnType.getTypeName()
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

            Optional<ParamInfo> correspondingParam =
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
}

