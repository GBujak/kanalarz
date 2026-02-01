package com.gbujak.kanalarz;

import com.gbujak.kanalarz.KanalarzStepReplayer.SearchResult;
import com.gbujak.kanalarz.annotations.RollbackOnly;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Entrypoint of the Kanalarz pipeline library
 */
@NullMarked
public class Kanalarz {

    private final ConcurrentHashMap<UUID, CancellableContextState>
        cancellableContexts = new ConcurrentHashMap<>();

    enum CancellableContextState {
        CANCELLABLE,
        CANCELLED,
        CANCELLED_FORCE_DEFER_ROLLBACK,
    }

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
    private static final ThreadLocal<@Nullable KanalarzContext>
        kanalarzContextThreadLocal = new InheritableThreadLocal<>();

    /**
     * Get current pipeline context
     * @return current pipeline context or null if not in a pipeline context
     */
    @Nullable
    public static KanalarzContext context() {
        return kanalarzContextThreadLocal.get();
    }

    /**
     * Get current pipeline context
     * @return current pipeline context or empty Optional if not in a context
     */
    public static Optional<KanalarzContext> contextOpt() {
        return (Optional<@NonNull KanalarzContext>) Optional.ofNullable(kanalarzContextThreadLocal.get());
    }

    /**
     * Get current pipeline context
     * @return current pipeline context
     * @throws IllegalStateException if not in a pipeline context
     */
    public static KanalarzContext contextOrThrow() {
        return contextOpt()
            .orElseThrow(() -> new IllegalStateException("Not within a context"));
    }

    @Nullable
    Object handleMethodInvocation(
        Object target,
        MethodInvocation invocation,
        StepsHolder stepsHolder,
        @Nullable Step step,
        @Nullable RollbackOnly rollbackOnly
    ) {

        var method = invocation.getMethod();
        var arguments = invocation.getArguments();

        if (step == null && rollbackOnly == null) {
            throw new KanalarzException.KanalarzInternalError(
                "Handling method invocation on step that isn't a step or a rollback marker, " +
                    "this should never happen!",
                null
            );
        }

        var context = context();
        if (context == null) {
            if (rollbackOnly != null) {
                return Utils.voidOrUnitValue(invocation.getMethod().getGenericReturnType());
            }

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
                rollbackOnly,
                stepStack.current(),
                stepStack.parentStepId(),
                context
            )
        );
    }

    @Nullable
    private Object handleInContextMethodExecution(
        Object target,
        Method method,
        Object[] arguments,
        StepsHolder stepsHolder,
        @Nullable Step step,
        @Nullable RollbackOnly rollbackOnly,
        UUID stepId,
        @Nullable UUID parentStepId,
        KanalarzContext context
    ) {
        switch (cancellableContexts.get(context.getId())) {
            case CANCELLED ->
                throw new KanalarzException.KanalarzContextCancelledException(false);
            case CANCELLED_FORCE_DEFER_ROLLBACK ->
                throw new KanalarzException.KanalarzContextCancelledException(true);
            default -> {}
        }

        StepInfoClasses.StepInfo stepInfo;
        String stepIdentifier;

        if (step != null) {
            stepInfo = stepsRegistry.getStepInfoOrThrow(stepsHolder, step);
            stepIdentifier = KanalarzStepsRegistry.stepIdentifier(stepsHolder, step);
        } else if (rollbackOnly != null) {
            stepInfo = stepsRegistry.getStepInfoOrThrow(stepsHolder, rollbackOnly);
            stepIdentifier = KanalarzStepsRegistry.stepIdentifier(stepsHolder, rollbackOnly);
        } else {
            throw new KanalarzException.KanalarzInternalError(
                "Handling invocation of a method that is both a step and a rollback-only marker!", null
            );
        }

        var serializeParametersInfo = Utils.makeSerializeParametersInfo(arguments, stepInfo);

        var stepReplayer = context.stepReplayer();
        var serializedParameters = serialization.serializeStepCalled(serializeParametersInfo, null);

        if (stepReplayer != null) {
            var foundStep = stepReplayer.findNextStep(stepIdentifier, serializedParameters);

            if (stepReplayer.isDone()) {
                context.clearStepReplayer();
            }

            switch (foundStep) {
                case SearchResult.Found(var value) -> {
                    return
                        StepOut.isTypeStepOut(stepInfo.returnType)
                            ? StepOut.ofNonNullOrThrow(value)
                            : value;
                }

                case SearchResult.FoundShouldRerun foundShouldRerun -> {}

                case SearchResult.NotFound notFound
                    when context.optionEnabled(Option.NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED) -> {}

                case SearchResult.NotFound notFound -> {
                    throw new KanalarzException.KanalarzNewStepBeforeReplayEndedException(
                        stepIdentifier + " was called with parameters it hadn't been called with before or " +
                            "it hadn't been called before at all: " + serializedParameters
                    );
                }
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
            serializedParameters,
            step != null && step.fallible(),
            stepInfo.rollbackMarker
        ));

        Object result = null;
        Throwable error = null;
        boolean unwrappedStepOut = false;
        String resultSerialized;
        try {
            result = rollbackOnly != null
                ? Utils.voidOrUnitValue(stepInfo.returnType)
                : method.invoke(target, arguments);
            if (step != null && step.fallible() && result == null) {
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
            failed,
            stepInfo.rollbackMarker
        ));

        if (failed) {
            if (step != null && step.fallible()) {
                return StepOut.err(error);
            } else {
                throw new KanalarzException.KanalarzStepFailedException(error);
            }
        } else {
            if (unwrappedStepOut && result == null) {
                throw new KanalarzException.KanalarzInternalError(
                    "Unwrapped StepOut had a null value! This should never happen!", null
                );
            }
            return unwrappedStepOut ? StepOut.of(result) : result;
        }
    }

    /**
     * Create a new pipeline context builder
     * @return new context builder
     */
    public KanalarzContextBuilder newContext() {
        return new KanalarzContextBuilder();
    }

    private <T> T inContext(
        Map<String, String> metadata,
        @Nullable UUID resumesContext,
        Function<KanalarzContext, T> body,
        EnumSet<Option> options,
        boolean resumeReplay
    ) {
        KanalarzContext previous = kanalarzContextThreadLocal.get();
        if (previous != null) {
            throw new KanalarzException.KanalarzIllegalUsageException(
                "Nested kanalarz context [" + previous.getId() + "] with metadata " + previous.fullMetadata()
            );
        }

        if (resumeReplay && resumesContext == null) {
            throw new KanalarzException.KanalarzIllegalUsageException(
                "Resume replay only makes sense when resuming some context! " +
                    "Tried to resume replay with a fresh context!"
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
            } catch (KanalarzException.KanalarzContextCancelledException e) {
                if (!options.contains(Option.DEFER_ROLLBACK) && !e.forceDeferRollback()) {
                    performRollback(
                        autoCloseableContext.context(),
                        e,
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
        var executedSteps = persistance.getExecutedStepsInContextInOrderOfExecution(resumesContext);

        if (options.contains(Option.OUT_OF_ORDER_REPLAY)) {
            return new KanalarzStepReplayer.KanalarzOutOfOrderStepReplayer(serialization, stepsRegistry, executedSteps);
        } else {
            return new KanalarzStepReplayer.KanalarzInOrderStepReplayer(serialization, stepsRegistry, executedSteps);
        }
    }

    private void rollbackInContext(
        Map<String, String> metadata,
        @Nullable UUID resumesContext,
        EnumSet<Option> options
    ) {
        try (var autoCloseableContext = new AutoCloseableContext(metadata, resumesContext, options, null)) {
            performRollback(autoCloseableContext.context(), null, options, null);
        }
    }

    private void performRollback(
        KanalarzContext context,
        @Nullable Throwable originalError,
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
                        it -> (Boolean) it.failed(),
                        (leftFailed, rightFailed) -> (Boolean) (leftFailed && rightFailed)
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

            if (Stream.of(rollback.rollback, rollback.rollbackOnly).filter(Objects::nonNull).count() != 1) {
                throw new KanalarzException.KanalarzInternalError(
                    ("Step [%s] is rollback and should have either a rollback info or a rollbackOnly info " +
                        "but had none or both!").formatted(rollback),
                    null
                );
            }

            boolean fallible;
            String rollbackIdentifier;
            if (rollback.rollback != null) {
                fallible = rollback.rollback.fallible();
                rollbackIdentifier = KanalarzStepsRegistry.rollbackIdentifier(rollback.stepsHolder, rollback.rollback);
            } else if (rollback.rollbackOnly != null) {
                fallible = rollback.rollbackOnly.fallible();
                rollbackIdentifier = KanalarzStepsRegistry.rollbackIdentifier(rollback.stepsHolder, rollback.rollbackOnly);
            } else {
                throw new KanalarzException.KanalarzInternalError("Unreachable!", null);
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

            @Nullable Object[] parameters = new Object[rollback.paramsInfo.size()];
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

                var serializedParameters = serialization.serializeStepCalled(
                    Utils.makeSerializeParametersInfo(parameters, rollback),
                    null
                );

                persistance.stepStarted(new KanalarzPersistence.StepStartedEvent(
                    context.getId(),
                    stepStack.current(),
                    Optional.empty(),
                    Optional.of(rollforward.stepId()),
                    context.fullMetadata(),
                    rollbackIdentifier,
                    rollback.description,
                    serializedParameters,
                    fallible,
                    stepInfo.rollbackMarker
                ));

                Object result = null;
                Throwable error = null;
                try {
                    if (rollback.method == null) {
                        throw new KanalarzException.KanalarzInternalError(
                            "Rollback had null method reference this should never happen!",
                            null
                        );
                    }
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
                    rollbackIdentifier,
                    rollback.description,
                    serializedResult,
                    failed,
                    stepInfo.rollbackMarker
                ));

                if (failed && !fallible && !options.contains(Option.ALL_ROLLBACK_STEPS_FALLIBLE)) {
                    throw new KanalarzException.KanalarzRollbackStepFailedException(originalError, error);
                }

                return null;
            });
        }
    }

    /**
     * Kanalarz pipeline context builder
     */
    public class KanalarzContextBuilder {

        @Nullable private UUID resumeContext;
        private final Map<String, String> metadata = new HashMap<>();
        private final EnumSet<Option> options = EnumSet.noneOf(Option.class);

        /**
         * Sets the new context ID. This can be a context that ran in the past, or it can be a freshly generated id.
         * If not provided the library will generate a new UUID.
         * @param resumes the ID to use
         * @return this to continue building
         */
        public KanalarzContextBuilder resumes(@Nullable UUID resumes) {
            this.resumeContext = resumes;
            return this;
        }

        /**
         * Enable a pipeline option
         * @param option option to enable
         * @return this to continue building
         */
        public KanalarzContextBuilder option(Option option) {
            return option(option, true);
        }

        /**
         * Set pipeline option enablement
         * @param option option to enable
         * @param enable whether the option should be enabled
         * @return this to continue building
         */
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

        /**
         * Add a metadata field to the metadata map. The metadata
         * is accessible through the pipeline context and is passed to
         * the pipeline library adapters with every step started / completed event.
         * @param key key of the metadata
         * @param value value of the metadata
         * @return this to continue building
         */
        public KanalarzContextBuilder metadata(String key, String value) {
            metadata.put(key, value);
            return this;
        }

        /**
         * Begin execution of the pipeline context
         * @param block {@link Function} containing the pipeline body
         * @return the return of the block parameter
         * @param <T> return type of block
         */
        public <T extends @Nullable Object> T start(Function<KanalarzContext, T> block) {
            validateDependantOptions();
            return inContext(metadata, resumeContext, block, options, false);
        }

        /**
         * Begin execution of the pipeline context in resume replay mode. This mode is only intended
         * for pipelines that already ran with the ID set using the `resumes` method.
         * The body of the pipeline is executed normally, but, if some step was executed previously with the same
         * arguments, the value from the previous execution is returned and the step is not executed.
         * When every step has been replayed, the resume-replay context is dropped and the pipeline behaves like
         * a standard pipeline.
         * <br>
         * <b>MAKE SURE NO SIDE EFFECTS HAPPEN OUTSIDE OF STEPS OR THEY WILL BE EXECUTED AGAIN</b>
         * <br>
         * The behavior of the resume-replay can be controlled by enabling the various options
         * using this builder before starting the pipeline:
         * <ul>
         *     <li>OUT_OF_ORDER_REPLAY - steps can be replayed in a different order than initially executed</li>
         *     <li>
         *         NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED - new steps can execute
         *         before all the previous steps were replayed (requires OUT_OF_ORDER_REPLAY to be enabled)
         *     </li>
         *     <li>
         *         IGNORE_NOT_REPLAYED_STEPS - steps that weren't replayed after the pipeline finished are ignored
         *         (by default the pipeline would fail and a normal rollback would be
         *         triggered unless DEFER_ROLLBACK was enabled)
         *     </li>
         *     <li>
         *         ROLLBACK_ONLY_NOT_REPLAYED_STEPS - steps that weren't replayed after the pipeline finished
         *         will be rolled back but the pipeline will not fail (by default the pipeline would fail and everything
         *         would be rolled back unless DEFER_ROLLBACK was enabled)
         *     </li>
         * </ul>
         * @param block {@link Function} containing the pipeline body
         * @param <T> return type of block
         * @return the return of the block parameter
         */
        public <T extends @Nullable Object> T startResumeReplay(Function<KanalarzContext, T> block) {
            validateDependantOptions();
            return inContext(metadata, resumeContext, block, options, true);
        }

        /**
         * Util method that takes a consumer instead of a function.
         * Does the same thing as the start method but doesn't return anything.
         * @param block body of the pipeline
         */
        public void consume(Consumer<KanalarzContext> block) {
            start(ctx -> {
                block.accept(ctx);
                return null;
            });
        }

        /**
         * Util method that takes a consumer instead of a function.
         * Does the same thing as the resumeReplay method but doesn't return anything.
         * @param block body of the pipeline
         */
        public void consumeResumeReplay(Consumer<KanalarzContext> block) {
            startResumeReplay(ctx -> {
                block.accept(ctx);
                return null;
            });
        }

        /**
         * Function that triggers an immediate rollback of the resumed context. To be used by pipelines that failed
         * with the DEFER_ROLLBACK option enabled to trigger a rollback. The rollback will be exactly as if the
         * DEFER_ROLLBACK option wasn't enabled and the pipeline failed. Can also be used with a pipeline that
         * succeeded.
         * @throws IllegalStateException if called without resuming a context
         */
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

    /**
     * Get running contexts globally.
     * @return unmodifiable set of running contexts
     */
    public Set<UUID> runningContexts() {
        return Collections.unmodifiableSet(cancellableContexts.keySet());
    }

    /**
     * Cancels the context. A {@link KanalarzException.KanalarzContextCancelledException} exception will be thrown
     * the next time a step is attempted to be executed within this context and on every following attempt.
     * A step started event will not be produced.
     * @param contextId context id to cancel
     * @throws IllegalStateException if the context has already been cancelled or is not running
     */
    public void cancelContext(UUID contextId) {
        Objects.requireNonNull(contextId);
        cancelContext(contextId, CancellableContextState.CANCELLED);
    }

    /**
     * Cancels the context. A {@link KanalarzException.KanalarzContextCancelledException} exception will be thrown
     * the next time a step is attempted to be executed within this context and on every following attempt.
     * A step started event will not be produced. A rollback will not be triggered.
     * @param contextId context id to cancel
     * @throws IllegalStateException if the context has already been cancelled or is not running
     */
    public void cancelContextForceDeferRollback(UUID contextId) {
        Objects.requireNonNull(contextId);
        cancelContext(contextId, CancellableContextState.CANCELLED_FORCE_DEFER_ROLLBACK);
    }

    private void cancelContext(UUID contextId, CancellableContextState newState) {
        if (newState == CancellableContextState.CANCELLABLE) {
            throw new IllegalArgumentException("Can't uncancel a context");
        }
        cancellableContexts.compute(contextId, (id, state) ->
            switch (state) {
                case CANCELLABLE -> newState;
                case null -> throw new IllegalStateException(
                    "Context [%s] is not currently running.".formatted(id));
                default -> throw new IllegalStateException(
                    "Context [%s] has already been cancelled.".formatted(id));
            }
        );
    }

    @NullMarked
    private class AutoCloseableContext implements AutoCloseable {

        private final KanalarzContext context;
        private final @Nullable UUID newContextId;

        AutoCloseableContext(
            Map<String, String> metadata,
            @Nullable UUID resumesContext,
            EnumSet<Option> options,
            @Nullable KanalarzStepReplayer stepReplayer
        ) {
            context = new KanalarzContext(
                resumesContext,
                options,
                stepReplayer
            );
            newContextId = context.getId();
            context.putAllMetadata(metadata);
            kanalarzContextThreadLocal.set(context);
            activeContexts.incrementAndGet();
            cancellableContexts.put(newContextId, CancellableContextState.CANCELLABLE);
        }


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

    /**
     * Options used to configure the pipeline execution
     */
    public enum Option {
        /**
         * Rollback steps will not be executed even if the pipeline fails (an exception is thrown out of the
         * pipeline context body). The rollback steps can be executed using the rollbackNow method of the pipeline
         * context builder
         */
        DEFER_ROLLBACK,

        /**
         * Pipeline will behave as if every rollback step was marked with fallible = true.
         * Failed steps will not trigger a failure of the rollback. They will be ignored and the pipeline
         * will continue the rollback. The recommended way to handle rollback failures is to let the rollback fail,
         * resolve the issue which caused it and try to rollback again using the rollbackNow method of the pipeline
         * context builder.
         */
        ALL_ROLLBACK_STEPS_FALLIBLE,

        /**
         * If any of the steps failed to rollback previously they will be ignored and skipped over
         * during the rollback. It's recommended to use the RETRY_FAILED_ROLLBACKS option instead.
         * By default, the rollback will fail if it encounters any steps that tried but failed to rollback
         * in a previous attempt.
         * This option is incompatible with RETRY_FAILED_ROLLBACKS.
         */
        SKIP_FAILED_ROLLBACKS,

        /**
         * If any of the steps failed to rollback previously, the pipeline will try to roll them back again
         * during the rollback.
         * By default, the rollback will fail if it encounters any steps that tried but failed to rollback
         * in a previous attempt.
         * This option is incompatible with SKIP_FAILED_ROLLBACKS.
         */
        RETRY_FAILED_ROLLBACKS,

        /**
         * Allows the steps to be replayed in a different order than in the original run of the pipeline. This
         * is not recommended when it can be avoided, because it prevents the pipeline from failing fast if the
         * steps are being replayed out of order. Instead, the pipeline will have to finish and then fail if any
         * non-replayed steps are left unless the IGNORE_NOT_REPLAYED_STEPS or ROLLBACK_ONLY_NOT_REPLAYED_STEPS
         * options are enabled.
         * <br>
         * This option is necessary if the pipeline has concurrency or executes the steps in a non-deterministic
         * order for some other reason. <b>This option is not necessary when using nested steps. They are handled
         * in a different way that doesn't require this option to be enabled.</b>
         * <br>
         * NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED is usually required as well when this option is required.
         */
        OUT_OF_ORDER_REPLAY,

        /**
         * Allows new steps to be executed before all steps from the previous run(s) of the pipeline were replayed
         * This is not recommended when it can be avoided, because it prevents the pipeline from failing fast.
         * Instead, the pipeline will have to finish and then fail if any
         * non-replayed steps are left unless the IGNORE_NOT_REPLAYED_STEPS or ROLLBACK_ONLY_NOT_REPLAYED_STEPS
         * options are enabled.
         * <br>
         * This option is usually required when OUT_OF_ORDER_REPLAY is required. This option requires
         * OUT_OF_ORDER_REPLAY to also be enabled.
         * <br>
         * This option is necessary if the pipeline has concurrency or executes the steps in a non-deterministic
         * order for some other reason. <b>This option is not necessary when using nested steps. They are handled
         * in a different way that doesn't require this option to be enabled.</b>
         */
        NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED(OUT_OF_ORDER_REPLAY),

        /**
         * Will not fail the whole pipeline nor trigger a rollback if some steps were not replayed. The non-replayed
         * steps will not be cleaned up. This option is not recommended when ROLLBACK_ONLY_NOT_REPLAYED_STEPS
         * can be used instead.
         * Note: the pipeline will still fail if new steps are executed before every step has been replayed
         * unless NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED is also enabled. The pipeline will still fail if
         * steps are replayed out of order unless OUT_OF_ORDER_REPLAY is also enabled.
         * This option is incompatible with ROLLBACK_ONLY_NOT_REPLAYED_STEPS.
         */
        IGNORE_NOT_REPLAYED_STEPS,

        /**
         * Will not fail the whole pipeline and rollback only the non-replayed steps if some steps were not replayed.
         * Note: the pipeline will still fail if new steps are executed before every step has been replayed
         * unless NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED is also enabled. The pipeline will still fail if
         * steps are replayed out of order unless OUT_OF_ORDER_REPLAY is also enabled.
         * This option is incompatible with IGNORE_NOT_REPLAYED_STEPS.
         */
        ROLLBACK_ONLY_NOT_REPLAYED_STEPS,
        ;

        @Nullable Option mustHave = null;

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

