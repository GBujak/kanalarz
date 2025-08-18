package com.gbujak.kanalarz;

import com.gbujak.kanalarz.StepInfoClasses.StepInfo;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.jetbrains.annotations.NotNull;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Function;

public class Kanalarz {

    private final Set<UUID> cancellableContexts = Collections.synchronizedSet(new HashSet<>());

    private final KanalarzStepsRegistry stepsRegistry;
    private final KanalarzSerialization serialization;
    private final KanalarzPersistance persistance;

    Kanalarz(
        KanalarzStepsRegistry stepsRegistry,
        KanalarzSerialization serialization,
        KanalarzPersistance persistance
    ) {
        this.stepsRegistry = stepsRegistry;
        this.serialization = serialization;
        this.persistance = persistance;
    }

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
    ) throws Throwable {

        var method = invocation.getMethod();
        var arguments = invocation.getArguments();

        var context = context();
        if (context == null) {
            return method.invoke(target, arguments);
        }

        var stepInfo = stepsRegistry.getStepInfo(stepsHolder, step);
        var serializeParametersInfo = makeSerializeParametersInfo(arguments, stepInfo);

        Object result = null;
        Throwable error = null;
        String resultSerialized;
        try {
            result = method.invoke(target, arguments);
            var stepOutResult =
                (StepOut<?>)
                    (Utils.isStepOut(stepInfo.returnType)
                        ? result
                        : StepOut.ok(result));
            resultSerialized = serialization.serializeStepExecution(
                serializeParametersInfo,
                new KanalarzSerialization.SerializeReturnInfo(
                    stepInfo.returnType,
                    stepOutResult,
                    stepInfo.returnIsSecret
                )
            );
        } catch (Throwable e) {
            resultSerialized = serialization.serializeStepExecution(
                serializeParametersInfo,
                new KanalarzSerialization.SerializeReturnInfo(
                    stepInfo.returnType,
                    StepOut.err(e),
                    stepInfo.returnIsSecret
                )
            );

            error = e;
        }

        persistance.stepCompleted(new KanalarzPersistance.StepCompletedEvent(
            context.getId(),
            UUID.randomUUID(),
            Collections.unmodifiableMap(context.fullMetadata()),
            step.identifier(),
            resultSerialized,
            /* failed = */ error != null
        ));

        if (error != null) {
            if (step.fallible()) {
                return StepOut.err(error);
            } else {
                throw error;
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
            };
        }
    }
}

