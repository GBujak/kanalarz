package com.gbujak.kanalarz;

import com.gbujak.kanalarz.StepInfoClasses.StepInfo;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.jetbrains.annotations.NotNull;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class Kanalarz {

    private final Set<UUID> cancellableContexts = Collections.synchronizedSet(new HashSet<>());

    private final KanalarzStepsRegistry stepsRegistry;
    private final KanalarzSerialization serialization;
    private final KanalarzPersistence persistance;

    Kanalarz(
        KanalarzStepsRegistry stepsRegistry,
        KanalarzSerialization serialization,
        KanalarzPersistence persistance
    ) {
        this.stepsRegistry = stepsRegistry;
        this.serialization = serialization;
        this.persistance = persistance;
    }

    private static final AtomicInteger activeContexts = new AtomicInteger();
    private static final ThreadLocal<KanalarzContext> kanalarzContextThreadLocal = new InheritableThreadLocal<>();

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
    ) {

        var method = invocation.getMethod();
        var arguments = invocation.getArguments();

        var context = context();
        if (context == null) {
            try {
                return method.invoke(target, arguments);
            } catch (Throwable error) {
                if (step.fallible()) {
                    return StepOut.err(error);
                } else {
                    if (error instanceof RuntimeException re) {
                        throw re;
                    } else {
                        throw new RuntimeException(error);
                    }
                }
            }
        }

        return context.withStepId(
            stepId -> handleInContextMethodExecution(
                target,
                method,
                arguments,
                stepsHolder,
                step,
                stepId,
                context
            )
        );
    }

    private Object handleInContextMethodExecution(
        Object target,
        Method method,
        Object[] arguments,
        StepsHolder stepsHolder,
        Step step,
        UUID stepId,
        KanalarzContext context
    ) {

        persistance.stepStarted(new KanalarzPersistence.StepStartedEvent(
            context.getId(),
            stepId,
            Optional.empty(),
            context.fullMetadata(),
            KanalarzStepsRegistry.stepIdentifier(stepsHolder, step),
            step.fallible()
        ));

        if (!cancellableContexts.contains(context.getId())) {
            throw new RuntimeException("Context was cancelled");
        }

        var stepInfo = stepsRegistry.getStepInfo(stepsHolder, step);
        var serializeParametersInfo = makeSerializeParametersInfo(arguments, stepInfo);

        Object result = null;
        Throwable error = null;
        String resultSerialized;
        try {
            result = method.invoke(target, arguments);
            if (step.fallible() && result == null) {
                throw new RuntimeException(
                    "Fallible step [%s] returned null instead of a StepOut instance!"
                        .formatted(KanalarzStepsRegistry.stepIdentifier(stepsHolder, step))
                );
            }
            if (result instanceof StepOut<?> stepOutResult) {
                result = stepOutResult.valueOrNull();
                error = stepOutResult.errorOrNull();
            }
            resultSerialized = serialization.serializeStepExecution(
                serializeParametersInfo,
                new KanalarzSerialization.SerializeReturnInfo(
                    stepInfo.returnType,
                    result,
                    error,
                    stepInfo.returnIsSecret
                )
            );
        } catch (Throwable e) {
            error = e;
            resultSerialized = serialization.serializeStepExecution(
                serializeParametersInfo,
                new KanalarzSerialization.SerializeReturnInfo(
                    stepInfo.returnType,
                    result,
                    error,
                    stepInfo.returnIsSecret
                )
            );
        }

        var failed = error != null;
        persistance.stepCompleted(new KanalarzPersistence.StepCompletedEvent(
            context.getId(),
            stepId,
            Collections.unmodifiableMap(context.fullMetadata()),
            KanalarzStepsRegistry.stepIdentifier(stepsHolder, step),
            resultSerialized,
            failed
        ));

        if (failed) {
            if (step.fallible()) {
                return StepOut.err(error);
            } else {
                if (error instanceof RuntimeException re) {
                    throw re;
                } else {
                    throw new RuntimeException(error);
                }
            }
        } else {
            return result;
        }
    }

    @NotNull
    private static ArrayList<KanalarzSerialization.SerializeParameterInfo> makeSerializeParametersInfo(
        Object[] arguments,
        StepInfo stepInfo
    ) {
        var serializeParametersInfo = new ArrayList<KanalarzSerialization.SerializeParameterInfo>(arguments.length);
        for (int i = 0; i < arguments.length; i++) {
            var arg = arguments[i];
            var paramInfo = stepInfo.paramsInfo.get(i);
            serializeParametersInfo.add(
                new KanalarzSerialization.SerializeParameterInfo(
                    paramInfo.paramName,
                    paramInfo.type,
                    arg,
                    paramInfo.secret
                )
            );
        }
        return serializeParametersInfo;
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
        T result = null;
        Throwable error = null;
        try {
            var context = new KanalarzContext(this);
            newContextId = context.getId();
            context.putAllMetadata(metadata);
            kanalarzContextThreadLocal.set(context);
            activeContexts.incrementAndGet();
            cancellableContexts.add(newContextId);
            result = body.apply(context);
        } catch (Throwable e) {
            performRollback(Objects.requireNonNull(newContextId));
            throw e;
        } finally {
            kanalarzContextThreadLocal.remove();
            activeContexts.decrementAndGet();
            if (newContextId != null) {
                cancellableContexts.remove(newContextId);
            }
        }

        return result;
    }

    private void performRollback(@NonNull UUID contextId) {
        var executedSteps = persistance.getExecutedStepsInContextInOrderOfExecution(contextId);
        for (var rollforward : executedSteps.reversed()) {
            if (rollforward.failed()) {
                continue;
            }

            var stepInfo = stepsRegistry.getStepInfoOrThrow(rollforward.stepIdentifier());
            if (stepInfo.rollback != null) {
                continue;
            }
            var rollback = stepsRegistry.getStepRollbackInfo(rollforward.stepIdentifier()).orElse(null);
            if (rollback == null) {
                continue;
            }

            if (rollback.rollback == null) {
                throw new RuntimeException(
                    "Library logic error: Step [%s] is rollback but had no rollback info"
                        .formatted(rollback)
                );
            }

            var deserializedParams = serialization.deserializeParameters(
                rollforward.serializedExecutionResult(),
                makeDeserializeParamsInfo(stepInfo.paramsInfo),
                stepInfo.returnType
            );
            if (deserializedParams.executionError() != null) {
                continue;
            }

            Object[] parameters = new Object[rollback.paramsInfo.size()];
            for (int i = 0; i < rollback.paramsInfo.size(); i++) {
                var paramInfo = rollback.paramsInfo.get(i);

                if (paramInfo.isRollforwardOutput) {
                    parameters[i] = deserializedParams.executionResult();
                } else {
                    parameters[i] = deserializedParams.parameters().get(paramInfo.paramName);
                }

                if (paramInfo.isNonNullable && parameters[i] == null) {
                    throw new RuntimeException(
                        "Trying to execute rollback for step [%s] but the non-nullable parameter [%s] is null"
                            .formatted(rollforward.stepIdentifier(), paramInfo.paramName)
                    );
                }
            }

            contextOrThrow().<Void>withStepId(stepId -> {

                persistance.stepStarted(new KanalarzPersistence.StepStartedEvent(
                    contextId,
                    stepId,
                    Optional.of(rollforward.stepId()),
                    contextOrThrow().fullMetadata(),
                    KanalarzStepsRegistry.stepIdentifier(rollback.stepsHolder, rollback.rollback),
                    rollback.rollback.fallible()
                ));

                Object result = null;
                Throwable error = null;
                try {
                    result = rollback.method.invoke(rollback.target, parameters);
                } catch (Throwable e) {
                    error = e;
                }

                var serializedResult = serialization.serializeStepExecution(
                    makeSerializeParametersInfo(parameters, stepInfo),
                    new KanalarzSerialization.SerializeReturnInfo(
                        rollback.returnType,
                        result,
                        error,
                        rollback.returnIsSecret
                    )
                );

                persistance.stepCompleted(new KanalarzPersistence.StepCompletedEvent(
                    contextId,
                    stepId,
                    context().fullMetadata(),
                    KanalarzStepsRegistry.stepIdentifier(rollback.stepsHolder, rollback.rollback),
                    serializedResult,
                    error != null
                ));

                if (!rollback.rollback.fallible()) {
                    if (error instanceof RuntimeException re) {
                        throw re;
                    } else {
                        throw new RuntimeException(error);
                    }
                }

                return null;
            });
        }
    }

    private List<KanalarzSerialization.DeserializeParameterInfo>
    makeDeserializeParamsInfo(List<StepInfoClasses.ParamInfo> paramsInfo) {
        return paramsInfo.stream()
            .map(it -> new KanalarzSerialization.DeserializeParameterInfo(it.paramName, it.type))
            .toList();
    }
}

