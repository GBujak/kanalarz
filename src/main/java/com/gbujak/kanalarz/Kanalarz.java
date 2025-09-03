package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            } catch (InvocationTargetException error) {
                if (step.fallible()) {
                    return StepOut.err(error.getTargetException());
                } else {
                    throw new KanalarzException.KanalarzStepFailedException(error.getTargetException());
                }
            } catch (Throwable e) {
                throw new KanalarzException.KanalarzInternalError(e.getMessage(), e);
            }
        }

        return context.withStepId(
            stepStack -> handleInContextMethodExecution(
                target,
                method,
                arguments,
                stepsHolder,
                step,
                stepStack.current(),
                stepStack.parentStepId(),
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
        @Nullable UUID parentStepId,
        KanalarzContext context
    ) {
        if (!cancellableContexts.contains(context.getId())) {
            throw new KanalarzException.KanalarzContextCancelledException();
        }

        var stepInfo = stepsRegistry.getStepInfoOrThrow(stepsHolder, step);
        var serializeParametersInfo = Utils.makeSerializeParametersInfo(arguments, stepInfo);
        var stepIdentifier = KanalarzStepsRegistry.stepIdentifier(stepsHolder, step);

        var stepReplayer = context.stepReplayer();
        if (stepReplayer != null) {
            var serializedParameters = serialization.serializeStepCalled(serializeParametersInfo, null);
            var foundStep = stepReplayer.findNextStep(stepIdentifier, serializedParameters);

            if (stepReplayer.isDone()) {
                context.clearStepReplayer();
            }

            if (foundStep == KanalarzStepReplayer.SearchResult.FOUND) {
                return StepOut.isTypeStepOut(stepInfo.returnType)
                    ? StepOut.of(stepReplayer.getNextStepReturnValue())
                    : stepReplayer.getNextStepReturnValue();
            } else if (
                foundStep == KanalarzStepReplayer.SearchResult.NOT_FOUND &&
                    !context.optionEnabled(Option.NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED)
            ) {
                throw new KanalarzException.KanalarzNewStepBeforeReplayEndedException(
                    stepIdentifier + " was called with parameters it hadn't been called with before or " +
                        "it hadn't been called before at all: " + serializedParameters
                );
            }
        }

        persistance.stepStarted(new KanalarzPersistence.StepStartedEvent(
            context.getId(),
            stepId,
            Optional.ofNullable(parentStepId),
            Optional.empty(),
            context.fullMetadata(),
            stepIdentifier,
            stepInfo.description,
            step.fallible()
        ));

        Object result = null;
        Throwable error = null;
        boolean unwrappedStepOut = false;
        String resultSerialized;
        try {
            result = method.invoke(target, arguments);
            if (step.fallible() && result == null) {
                throw new KanalarzException.KanalarzIllegalUsageException(
                    "Fallible step [%s] returned null instead of a StepOut instance!"
                        .formatted(stepIdentifier)
                );
            }
            if (result instanceof StepOut<?> stepOutResult) {
                unwrappedStepOut = true;
                result = stepOutResult.valueOrNull();
                error = stepOutResult.errorOrNull();
            }
            resultSerialized = serialization.serializeStepCalled(
                serializeParametersInfo,
                new KanalarzSerialization.SerializeReturnInfo(
                    stepInfo.returnType,
                    result,
                    error,
                    stepInfo.returnIsSecret
                )
            );
        } catch (InvocationTargetException e) {
            error = e.getTargetException();
            resultSerialized = serialization.serializeStepCalled(
                serializeParametersInfo,
                new KanalarzSerialization.SerializeReturnInfo(
                    stepInfo.returnType,
                    result,
                    error,
                    stepInfo.returnIsSecret
                )
            );
        } catch (Throwable e) {
            throw new KanalarzException.KanalarzInternalError(
                "Error calling step [%s]".formatted(stepIdentifier),
                e
            );
        }

        var failed = error != null;
        persistance.stepCompleted(new KanalarzPersistence.StepCompletedEvent(
            context.getId(),
            stepId,
            Optional.ofNullable(parentStepId),
            Optional.empty(),
            Collections.unmodifiableMap(context.fullMetadata()),
            stepIdentifier,
            stepInfo.description,
            resultSerialized,
            failed
        ));

        if (failed) {
            if (step.fallible()) {
                return StepOut.err(error);
            } else {
                throw new KanalarzException.KanalarzStepFailedException(error);
            }
        } else {
            return unwrappedStepOut
                // StepOut can't hold a null value, so unwrapped value should never be null
                ? StepOut.of(Objects.requireNonNull(result))
                : result;
        }
    }

    @NonNull
    public KanalarzContextBuilder newContext() {
        return new KanalarzContextBuilder();
    }

    private <T> T inContext(
        @NonNull Map<String, String> metadata,
        @Nullable UUID resumesContext,
        @NonNull Function<KanalarzContext, T> body,
        @NonNull EnumSet<Option> options,
        boolean resumeReplay
    ) {
        KanalarzContext previous = kanalarzContextThreadLocal.get();
        if (previous != null) {
            throw new KanalarzException.KanalarzIllegalUsageException(
                "Nested kanalarz context [" + previous.getId() + "] with metadata " + previous.fullMetadata()
            );
        }

        var replayer = resumeReplay
            ? createStepReplayer(resumesContext, options)
            : null;

        try (var autoCloseableContext = new AutoCloseableContext(metadata, resumesContext, options, replayer)) {
            try {
                var result = body.apply(autoCloseableContext.context());
                if (replayer != null) {
                    var unreplayed = replayer.unreplayed();
                    if (!unreplayed.isEmpty()) {
                        if (options.contains(Option.ROLLBACK_ONLY_NOT_REPLAYED_STEPS)) {
                            performRollback(
                                autoCloseableContext.context(),
                                new KanalarzException.KanalarzStepsWereNotReplayedAndWillPartiallyRollbackException(),
                                options,
                                unreplayed.stream()
                                    .map(KanalarzPersistence.StepExecutedInfo::stepId)
                                    .collect(Collectors.toUnmodifiableSet())
                            );
                        } else if (!options.contains(Option.IGNORE_NOT_REPLAYED_STEPS)) {
                            throw new KanalarzException.KanalarzNotAllStepsReplayedException(
                                unreplayed.stream()
                                    .map(KanalarzPersistence.StepExecutedInfo::stepIdentifier)
                                    .toList()
                                    .toString()
                            );
                        }
                    }
                }
                return result;
            } catch (KanalarzException.KanalarzInternalError e) {
                throw e;
            } catch (KanalarzException.KanalarzStepFailedException e) {
                if (!options.contains(Option.DEFER_ROLLBACK)) {
                    performRollback(
                        autoCloseableContext.context(),
                        e.getInitialStepFailedException(),
                        options,
                        null
                    );
                }
                throw e;
            } catch (Throwable e) {
                if (!options.contains(Option.DEFER_ROLLBACK)) {
                    performRollback(
                        autoCloseableContext.context(),
                        e,
                        options,
                        null
                    );
                }
                throw new KanalarzException.KanalarzThrownOutsideOfStepException(e);
            }
        }
    }

    private KanalarzStepReplayer createStepReplayer(UUID resumesContext, EnumSet<Option> options) {
        if (resumesContext == null) {
            throw new KanalarzException.KanalarzIllegalUsageException(
                "Resume replay only makes sense when resuming some context! " +
                    "Tried to resume replay with a fresh context!"
            );
        }

        var executedSteps = persistance.getExecutedStepsInContextInOrderOfExecution(resumesContext);

        if (options.contains(Option.OUT_OF_ORDER_REPLAY)) {
            return new KanalarzStepReplayer.KanalarzOutOfOrderStepReplayer(serialization, stepsRegistry, executedSteps);
        } else {
            return new KanalarzStepReplayer.KanalarzInOrderStepReplayer(serialization, stepsRegistry, executedSteps);
        }
    }

    private void rollbackInContext(
        @NonNull Map<String, String> metadata,
        @Nullable UUID resumesContext,
        @NonNull EnumSet<Option> options
    ) {
        try (var autoCloseableContext = new AutoCloseableContext(metadata, resumesContext, options, null)) {
            performRollback(autoCloseableContext.context(), null, options, null);
        }
    }

    private void performRollback(
        @NonNull KanalarzContext context,
        Throwable originalError,
        EnumSet<Option> options,
        @Nullable Set<UUID> specificStepsToRollbackOnly
    ) {
        var executedSteps = persistance.getExecutedStepsInContextInOrderOfExecution(context.getId());
        var executedRollbacks =
            executedSteps.stream()
                .filter(it -> it.wasRollbackFor().isPresent())
                .collect(
                    Collectors.toMap(
                        it -> it.wasRollbackFor().get(),
                        KanalarzPersistence.StepExecutedInfo::failed,
                        (leftFailed, rightFailed) -> leftFailed && rightFailed
                    ));
        var stepsToRollback =
            executedSteps.reversed().stream()
                .filter(it -> it.wasRollbackFor().isEmpty())
                .filter(it ->
                    specificStepsToRollbackOnly == null ||
                        specificStepsToRollbackOnly.contains(it.stepId())
                )
                .toList();

        for (var rollforward : stepsToRollback) {
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
                throw new KanalarzException.KanalarzInternalError(
                    "Step [%s] is rollback but had no rollback info"
                        .formatted(rollback),
                    null
                );
            }

            var executedRollbackFailed = executedRollbacks.get(rollforward.stepId());
            if (executedRollbackFailed != null) {
                if (!executedRollbackFailed || options.contains(Option.SKIP_FAILED_ROLLBACKS)) {
                    continue;
                }
                if (!options.contains(Option.RETRY_FAILED_ROLLBACKS)) {
                    throw new KanalarzException.KanalarzRollbackStepFailedException(
                        originalError,
                        new IllegalStateException(
                            "Step already tried to rollback and failed. Use SKIP_FAILED_ROLLBACKS or " +
                                "RETRY_FAILED_ROLLBACKS to proceed."
                        )
                    );
                }
            }


            var deserializedParams = serialization.deserializeParameters(
                rollforward.serializedExecutionResult(),
                Utils.makeDeserializeParamsInfo(stepInfo.paramsInfo),
                StepOut.unwrapStepOutType(stepInfo.returnType)
            );
            if (deserializedParams.executionError() != null) {
                continue;
            }

            Object[] parameters = new Object[rollback.paramsInfo.size()];
            for (int i = 0; i < rollback.paramsInfo.size(); i++) {
                var paramInfo = rollback.paramsInfo.get(i);

                if (paramInfo.isRollforwardOutput) {
                    parameters[i] = deserializedParams.executionResult();
                    if (parameters[i] instanceof StepOut<?> stepOut) {
                        parameters[i] = stepOut.valueOrThrow();
                    }
                } else {
                    parameters[i] = deserializedParams.parameters().get(paramInfo.paramName);
                }

                if (paramInfo.isNonNullable && parameters[i] == null) {
                    throw new KanalarzException.KanalarzIllegalUsageException(
                        "Trying to execute rollback for step [%s] but the non-nullable parameter [%s] is null"
                            .formatted(rollforward.stepIdentifier(), paramInfo.paramName)
                    );
                }
            }

            context.withStepId(stepStack -> {
                persistance.stepStarted(new KanalarzPersistence.StepStartedEvent(
                    context.getId(),
                    stepStack.current(),
                    Optional.empty(),
                    Optional.of(rollforward.stepId()),
                    context.fullMetadata(),
                    KanalarzStepsRegistry.rollbackIdentifier(rollback.stepsHolder, rollback.rollback),
                    rollback.description,
                    rollback.rollback.fallible()
                ));

                Object result = null;
                Throwable error = null;
                try {
                    result = rollback.method.invoke(rollback.target, parameters);
                } catch (InvocationTargetException e) {
                    error = e.getTargetException();
                } catch (Throwable e) {
                    throw new KanalarzException.KanalarzInternalError(e.getMessage(), e);
                }

                var serializedResult = serialization.serializeStepCalled(
                    Utils.makeSerializeParametersInfo(parameters, rollback),
                    new KanalarzSerialization.SerializeReturnInfo(
                        rollback.returnType,
                        result,
                        error,
                        rollback.returnIsSecret
                    )
                );

                boolean failed = error != null;
                persistance.stepCompleted(new KanalarzPersistence.StepCompletedEvent(
                    context.getId(),
                    stepStack.current(),
                    Optional.empty(),
                    Optional.of(rollforward.stepId()),
                    context.fullMetadata(),
                    KanalarzStepsRegistry.rollbackIdentifier(rollback.stepsHolder, rollback.rollback),
                    rollback.description,
                    serializedResult,
                    failed
                ));

                if (failed && !rollback.rollback.fallible() && !options.contains(Option.ALL_ROLLBACK_STEPS_FALLIBLE)) {
                    throw new KanalarzException.KanalarzRollbackStepFailedException(originalError, error);
                }

                return null;
            });
        }
    }

    public class KanalarzContextBuilder {

        @Nullable private UUID resumeContext;
        @NonNull private final Map<String, String> metadata = new HashMap<>();
        private final EnumSet<Option> options = EnumSet.noneOf(Option.class);

        @NonNull
        public KanalarzContextBuilder resumes(@Nullable UUID resumes) {
            this.resumeContext = resumes;
            return this;
        }

        @NonNull
        public KanalarzContextBuilder option(Option option) {
            return option(option, true);
        }

        @NonNull
        public KanalarzContextBuilder option(Option option, boolean enable) {
            if (enable) {
                var newOptions = EnumSet.copyOf(options);
                options.add(option);
                for (var incompatible : Option.incompatible) {
                    if (newOptions.containsAll(incompatible)) {
                        throw new IllegalStateException(
                            "Incompatible option combination found in config: " + incompatible
                        );
                    }
                }
                options.add(option);
            } else {
                options.remove(option);
            }
            return this;
        }

        @NonNull
        public KanalarzContextBuilder metadata(@NonNull String key, @NonNull String value) {
            metadata.put(key, value);
            return this;
        }

        public <T> T start(Function<KanalarzContext, T> block) {
            validateDependantOptions();
            return inContext(metadata, resumeContext, block, options, false);
        }

        public <T> T startResumeReplay(Function<KanalarzContext, T> block) {
            validateDependantOptions();
            return inContext(metadata, resumeContext, block, options, true);
        }

        public void consume(Consumer<KanalarzContext> block) {
            start(ctx -> {
                block.accept(ctx);
                return null;
            });
        }

        public void consumeResumeReplay(Consumer<KanalarzContext> block) {
            startResumeReplay(ctx -> {
                block.accept(ctx);
                return null;
            });
        }

        public void rollbackNow() {
            validateDependantOptions();
            if (resumeContext == null) {
                throw new IllegalStateException(
                    "Immediate rollback of a context that doesn't resume anything makes no sense!"
                );
            }
            rollbackInContext(metadata, resumeContext, options);
        }

        private void validateDependantOptions() {
            for (var option : options) {
                if (option.mustHave != null && !options.contains(option.mustHave)) {
                    throw new IllegalStateException(
                        "Option [%s] only works with option [%s] also enabled"
                            .formatted(option.name(), option.mustHave.name())
                    );
                }
            }
        }
    }

    private class AutoCloseableContext implements AutoCloseable {

        private final KanalarzContext context;
        private final UUID newContextId;

        AutoCloseableContext(
            Map<String, String> metadata,
            UUID resumesContext,
            EnumSet<Option> options,
            KanalarzStepReplayer stepReplayer
        ) {
            context = new KanalarzContext(
                Kanalarz.this,
                resumesContext,
                options,
                stepReplayer
            );
            newContextId = context.getId();
            context.putAllMetadata(metadata);
            kanalarzContextThreadLocal.set(context);
            activeContexts.incrementAndGet();
            cancellableContexts.add(newContextId);
        }

        @NonNull
        public KanalarzContext context() {
            return Objects.requireNonNull(context);
        }

        @Override
        public void close() {
            kanalarzContextThreadLocal.remove();
            activeContexts.decrementAndGet();
            if (newContextId != null) {
                cancellableContexts.remove(newContextId);
            }
        }
    }

    public enum Option {
        DEFER_ROLLBACK,
        ALL_ROLLBACK_STEPS_FALLIBLE,
        SKIP_FAILED_ROLLBACKS,
        RETRY_FAILED_ROLLBACKS,
        OUT_OF_ORDER_REPLAY,
        NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED(OUT_OF_ORDER_REPLAY),
        IGNORE_NOT_REPLAYED_STEPS,
        ROLLBACK_ONLY_NOT_REPLAYED_STEPS,
        ;

        Option mustHave = null;

        Option() { }
        Option(Option mustHave) {
            this.mustHave = mustHave;
        }

        private static final List<EnumSet<Option>> incompatible = List.of(
            EnumSet.of(SKIP_FAILED_ROLLBACKS, RETRY_FAILED_ROLLBACKS),
            EnumSet.of(IGNORE_NOT_REPLAYED_STEPS, ROLLBACK_ONLY_NOT_REPLAYED_STEPS)
        );
    }
}

