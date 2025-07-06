package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.*;
import com.gbujak.kanalarz.StepInfoClasses.*;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.*;
import java.util.*;

public class KanalarzContext {

    private final Map<String, StepInfo> steps = new HashMap<>();
    private final Map<String, String> rollbackSteps = new HashMap<>();

    Object handleMethodInvocation(
        Object target,
        MethodInvocation invocation,
        StepsComponent stepsComponent,
        Step step
    ) throws InvocationTargetException, IllegalAccessException {
        var method = invocation.getMethod();
        var arguments = invocation.getArguments();
        return method.invoke(target, arguments);
    }

    synchronized void registerRollforwardStep(
        Object target,
        Method method,
        StepsComponent stepsComponent,
        Step step
    ) {
        String stepIdentifier = "%s:%s".formatted(stepsComponent.identifier(), step.identifier());
        if (this.steps.containsKey(stepIdentifier)) {
            throw new RuntimeException("Duplicated step identifier %s".formatted(stepIdentifier));
        }

        var stepInfo = new StepInfo();
        stepInfo.target = target;
        stepInfo.method = method;
        stepInfo.returnType = method.getGenericReturnType();
        stepInfo.paramsInfo = new ArrayList<>(method.getParameterCount());

        for (var param : method.getParameters()) {
            stepInfo.paramsInfo.add(ParamInfo.fromParam(param));
        }

        steps.put(stepIdentifier, stepInfo);
    }

    synchronized void registerRollbackStep(
        Object target,
        Method method,
        StepsComponent stepsComponent,
        Rollback rollback
    ) {
        var stepIdentifier = "%s:%s".formatted(stepsComponent.identifier(), rollback.forStep());
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

        var stepInfo = new StepInfo();
        stepInfo.target = target;
        stepInfo.method = method;
        stepInfo.returnType = method.getGenericReturnType();
        stepInfo.paramsInfo = new ArrayList<>(method.getParameterCount());

        for (var param : method.getParameters()) {
            var paramInfo = ParamInfo.fromParam(param);
            stepInfo.paramsInfo.add(paramInfo);
            if (param.getAnnotation(RollforwardOut.class) != null) {
                paramInfo.isRollforwardOutput = true;
                if (!param.getParameterizedType().equals(rollforwardStep.returnType)) {
                    throw new RuntimeException(
                        ("Rollback step [%s] declares a rollforward step [%s] output parameter [%s] but the return type " +
                            "of the rollforward step and that parameter are different! ([%s] and [%s])")
                            .formatted(
                                rollbackIdentifier,
                                stepIdentifier,
                                paramInfo.paramName,
                                paramInfo.type.getTypeName(),
                                rollforwardStep.returnType.getTypeName()
                            )
                    );
                }
                continue;
            }

            Optional<ParamInfo> correspondingParam =
                rollforwardStep.paramsInfo.stream()
                    .filter(it -> it.paramName.equals(paramInfo.paramName))
                    .findAny();

            if (correspondingParam.isEmpty()) {
                throw new RuntimeException(
                    "Could not find corresponding param for param [%s] in step [%s] when processing rollback step [%s]"
                        .formatted(paramInfo.paramName, stepIdentifier, rollbackIdentifier)
                );
            }

            if (!correspondingParam.get().type.equals(paramInfo.type)) {
                throw new RuntimeException(
                    ("Rollback step [%s] declares a parameter [%s] from the rollforward step [%s] " +
                        "but the types are different! ([%s] and [%s])")
                        .formatted(
                            rollbackIdentifier,
                            paramInfo.paramName,
                            stepIdentifier,
                            paramInfo.type.getTypeName(),
                            correspondingParam.get().type.getTypeName()
                        )
                );
            }
        }

        steps.put(rollbackIdentifier, stepInfo);
    }
}

