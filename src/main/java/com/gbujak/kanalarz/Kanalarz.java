package com.gbujak.kanalarz;

import com.gbujak.kanalarz.StepInfoClasses.ParamInfo;
import com.gbujak.kanalarz.StepInfoClasses.StepInfo;
import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.jetbrains.annotations.NotNull;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;

public class Kanalarz {

    private final Map<String, StepInfo> steps = new HashMap<>();
    private final Map<String, String> rollbackStepsForRollforwardSteps = new HashMap<>();
    private final Set<UUID> cancellableContexts = Collections.synchronizedSet(new HashSet<>());

    private static final ThreadLocal<KanalarzContext> kanalarzContextThreadLocal = new ThreadLocal<>();

    @Nullable
    public static KanalarzContext context() {
        return kanalarzContextThreadLocal.get();
    }

    @NonNull
    public static Optional<KanalarzContext> contextOpt() {
        return Optional.ofNullable(kanalarzContextThreadLocal.get());
    }

    @NonNull
    public static KanalarzContext contextOrThrow() {
        return contextOpt()
            .orElseThrow(() -> new IllegalStateException("Not within a context"));
    }

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
        Step step,
        boolean returnIsSecret
    ) {
        String stepIdentifier = stepIdentifier(stepsHolder, step);
        if (this.steps.containsKey(stepIdentifier)) {
            throw new RuntimeException("Duplicated step identifier %s".formatted(stepIdentifier));
        }
        var stepInfo = StepInfo.createNew(target, method, step, returnIsSecret);
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

        StepInfo rollforwardStep = steps.get(stepIdentifier);
        if (rollforwardStep == null) {
            throw new RuntimeException(
                "Could not find step [%s] which is the rollforward step of a rollback step [%s] being processed"
                    .formatted(stepIdentifier, rollback)
            );
        }

        var stepInfo = StepInfo.createNew(target, method, rollback, returnIsSecret);

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

    public <T> T inContext(Function<KanalarzContext, T> body) {
        return inContext(Map.of(), body);
    }

    public <T> T inContext(Map<String, String> metadata, Function<KanalarzContext, T> body) {
        KanalarzContext previous = kanalarzContextThreadLocal.get();
        if (previous != null) {
            throw new IllegalStateException(
                "Nested kanalarz context [" + previous.getId() + "] with metadata " + previous.fullMetadata()
            );
        }

        UUID newContextId = null;
        try {
            var context = new KanalarzContext(this);
            newContextId = context.getId();
            cancellableContexts.add(newContextId);
            context.putAllMetadata(metadata);
            kanalarzContextThreadLocal.set(context);
            return body.apply(context);
        } finally {
            kanalarzContextThreadLocal.remove();
            if (newContextId != null) {
                cancellableContexts.remove(newContextId);
            }
        }
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

    private static boolean isStepOut(Type type) {
        if (type instanceof ParameterizedType pt) {
            return pt.getRawType().equals(StepOut.class);
        } else if (type instanceof Class<?> clazz) {
            return clazz.equals(StepOut.class);
        }
        return false;
    }
}

