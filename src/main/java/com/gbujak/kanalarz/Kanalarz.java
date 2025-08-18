package com.gbujak.kanalarz;

import com.gbujak.kanalarz.StepInfoClasses.StepInfo;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Function;

public class Kanalarz {

    private final Set<UUID> cancellableContexts = Collections.synchronizedSet(new HashSet<>());
    private final KanalarzStepsRegistry stepsRegistry;

    Kanalarz(KanalarzStepsRegistry stepsRegistry) {
        this.stepsRegistry = stepsRegistry;
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
    ) throws InvocationTargetException, IllegalAccessException {
        var method = invocation.getMethod();
        var arguments = invocation.getArguments();
        return method.invoke(target, arguments);
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
}

