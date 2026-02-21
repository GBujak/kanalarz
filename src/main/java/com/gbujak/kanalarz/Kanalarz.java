package com.gbujak.kanalarz;

import com.gbujak.kanalarz.StepReplayer.SearchResult;
import com.gbujak.kanalarz.annotations.RollbackOnly;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Entrypoint of the Kanalarz pipeline library
 */
@NullMarked
public class Kanalarz {

    private static final ThreadLocal<@Nullable ContextStack> kanalarzContextThreadLocal = new ThreadLocal<>();
    private static final ExecutorService forkExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final ConcurrentHashMap<UUID, KanalarzContext> contexts = new ConcurrentHashMap<>();

    private final KanalarzStepsRegistry stepsRegistry;
    private final KanalarzSerialization serialization;
    private final KanalarzPersistence persistence;

    Kanalarz(
        KanalarzStepsRegistry stepsRegistry,
        KanalarzSerialization serialization,
        KanalarzPersistence persistence
    ) {
        this.stepsRegistry = stepsRegistry;
        this.serialization = serialization;
        this.persistence = persistence;
    }

    public record ContextStack(
        KanalarzContext context,
        @Nullable ContextStack parents
    ) {

        private ContextStack copy() {
            return new ContextStack(context.copy(), parents != null ? parents.copy() : null);
        }

        private ContextStack copyWithNewThreadId() {
            return new ContextStack(context.copyWithNewThreadId(), parents != null ? parents.copy() : null);
        }

        private UUID contextId() {
            return context.id();
        }

        private List<UUID> contextIds() {
            return Stream.iterate(this, Objects::nonNull, ContextStack::parents)
                .filter(Objects::nonNull)
                .map(ContextStack::contextId)
                .toList()
                .reversed();
        }

        private UUID stepIdOrThrow() {
            return Optional.ofNullable(context.stepStack())
                .orElseThrow(() -> new KanalarzException.KanalarzInternalError("No steps active!", null))
                .current();
        }

        private Optional<UUID> parentStepId() {
            return Optional.ofNullable(context.stepStack())
                .map(KanalarzContext.StepStack::parents)
                .map(KanalarzContext.StepStack::current);
        }
    }

    /**
     * Get current pipeline context
     * @return current pipeline context or null if not in a pipeline context
     */
    @Nullable
    public static ContextStack contextStackOrNull() {
        return kanalarzContextThreadLocal.get();
    }

    /**
     * Get current pipeline context
     * @return current pipeline context or empty Optional if not in a context
     */
    public static Optional<ContextStack> contextStack() {
        return Optional.ofNullable(kanalarzContextThreadLocal.get());
    }

    /**
     * Get current pipeline context
     * @return current pipeline context
     * @throws IllegalStateException if not in a pipeline context
     */
    public static ContextStack contextStackOrThrow() {
        return contextStack().orElseThrow(KanalarzException.KanalarzNoContextException::new);
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

        var contextStack = contextStackOrThrow();
        contextStack.context().yield();

        return contextStack.context().withNewStep(
            stepStack -> handleInContextMethodExecution(
                target,
                method,
                arguments,
                stepsHolder,
                step,
                rollbackOnly,
                contextStack.context()
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
        KanalarzContext context
    ) {

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
                "Handling invocation of a method that is neither a step nor a rollback-only marker!", null
            );
        }

        var serializeParametersInfo = Utils.makeSerializeParametersInfo(arguments, stepInfo);

        var stepReplayer = context.stepReplayer();
        var serializedParameters = serialization.serializeStepCalled(serializeParametersInfo, null);

        if (stepReplayer != null) {
            var foundStep = stepReplayer.findNextStep(
                stepIdentifier,
                serializedParameters
            );

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

                case SearchResult.FoundShouldRerun ignored -> {}

                case SearchResult.NotFound ignored
                    when context.optionEnabled(Option.NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED) -> {}

                case SearchResult.NotFound ignored ->
                    throw new KanalarzException.KanalarzNewStepBeforeReplayEndedException(
                        stepIdentifier + " was called with parameters it hadn't been called with before or " +
                            "it hadn't been called before at all: " + serializedParameters
                    );
            }
        }

        var contextStack = contextStackOrThrow();
        persistence.stepStarted(new KanalarzPersistence.StepStartedEvent(
            contextStack.contextIds(),
            contextStack.stepIdOrThrow(),
            contextStack.parentStepId(),
            Optional.empty(),
            context.fullMetadata(),
            stepIdentifier,
            stepInfo.description,
            serializedParameters,
            step != null && step.fallible(),
            stepInfo.rollbackMarker,
            contextStack.context().threadId()
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
        var contextStackAfterExecute = contextStackOrThrow();
        persistence.stepCompleted(new KanalarzPersistence.StepCompletedEvent(
            contextStack.contextIds(),
            contextStackAfterExecute.stepIdOrThrow(),
            contextStackAfterExecute.parentStepId(),
            Optional.empty(),
            Collections.unmodifiableMap(context.fullMetadata()),
            stepIdentifier,
            stepInfo.description,
            resultSerialized,
            failed,
            stepInfo.rollbackMarker,
            contextStack.context().threadId()
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
        @Nullable String identifier,
        boolean resumeReplay
    ) {
        if (resumeReplay && resumesContext == null) {
            throw new KanalarzException.KanalarzIllegalUsageException(
                "Resume replay only makes sense when resuming some context! " +
                    "Tried to resume replay with a fresh context!"
            );
        }

        var replayer = resumeReplay
            ? createStepReplayer(resumesContext, options)
            : null;

        try (
            var autoCloseableContext =
                new AutoCloseableContext(metadata, resumesContext, options, replayer, identifier)
        ) {
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
                    performRollback(autoCloseableContext.context(), e.getInitialStepFailedException(), options, null);
                }
                throw e;
            } catch (KanalarzException.KanalarzContextCancelledException e) {
                if (!options.contains(Option.DEFER_ROLLBACK) && !e.forceDeferRollback()) {
                    performRollback(autoCloseableContext.context(), e, options, null);
                }
                throw e;
            } catch (Throwable e) {
                if (!options.contains(Option.DEFER_ROLLBACK)) {
                    performRollback(autoCloseableContext.context(), e, options, null);
                }
                throw new KanalarzException.KanalarzThrownOutsideOfStepException(e);
            }
        }
    }

    private StepReplayer createStepReplayer(UUID resumesContext, EnumSet<Option> options) {
        var executedSteps = persistence.getExecutedStepsInContextInOrderOfExecution(resumesContext);

        if (options.contains(Option.OUT_OF_ORDER_REPLAY)) {
            if (!options.contains(Option.ALLOW_UNSAFE_REPLAY_OF_AMBIGUOUS_OUT_OF_ORDER_CALLS)) {
                Utils.throwOnAmbiguousOutOfOrderStepReplay(executedSteps, serialization);
            }
            return new StepReplayer.OutOfOrderStepReplayer(serialization, stepsRegistry, executedSteps, resumesContext);
        } else {
            return new StepReplayer.InOrderStepReplayer(serialization, stepsRegistry, executedSteps, resumesContext);
        }
    }

    private void rollbackInContext(
        Map<String, String> metadata,
        @Nullable UUID resumesContext,
        EnumSet<Option> options,
        @Nullable String identifier
    ) {
        try (
            var autoCloseableContext =
                new AutoCloseableContext(metadata, resumesContext, options, null, identifier)
        ) {
            performRollback(autoCloseableContext.context(), null, options, null);
        }
    }

    private void performRollback(
        KanalarzContext context,
        @Nullable Throwable originalError,
        EnumSet<Option> options,
        @Nullable Set<UUID> specificStepsToRollbackOnly
    ) {
        var executedSteps = persistence.getExecutedStepsInContextInOrderOfExecution(context.id());
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

            context.withNewStep(stepStack -> {

                var serializedParameters = serialization.serializeStepCalled(
                    Utils.makeSerializeParametersInfo(parameters, rollback),
                    null
                );

                var contextStack = contextStackOrThrow();
                persistence.stepStarted(new KanalarzPersistence.StepStartedEvent(
                    contextStack.contextIds(),
                    contextStack.stepIdOrThrow(),
                    contextStack.parentStepId(),
                    Optional.of(rollforward.stepId()),
                    context.fullMetadata(),
                    rollbackIdentifier,
                    rollback.description,
                    serializedParameters,
                    fallible,
                    stepInfo.rollbackMarker,
                    contextStack.context().threadId()
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
                var contextStackAfterExecute = contextStackOrThrow();
                persistence.stepCompleted(new KanalarzPersistence.StepCompletedEvent(
                    contextStack.contextIds(),
                    contextStackAfterExecute.stepIdOrThrow(),
                    contextStackAfterExecute.parentStepId(),
                    Optional.of(rollforward.stepId()),
                    context.fullMetadata(),
                    rollbackIdentifier,
                    rollback.description,
                    serializedResult,
                    failed,
                    stepInfo.rollbackMarker,
                    contextStack.context().threadId()
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
        @Nullable private String identifier;
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
         * Set an optional identifier for the context. This is used in out-of-order resume-replay to determine which
         * subcontext the library should start replaying. If this is omitted, the library will replay contexts in the
         * order in which they are being re-executed and in which they were provided from the persistence bean.
         * If the contexts are being executed in a non-deterministic order, and you launch multiple subcontexts one
         * after the other, the first context off the queue has a very high chance of not being the right one. In that
         * case the replay will fail.
         * @param identifier the identifier of the context
         * @return this to continue building
         */
        public KanalarzContextBuilder identifier(String identifier) {
            this.identifier = identifier;
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
            return inContext(metadata, resumeContext, block, options, identifier, false);
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
            return inContext(metadata, resumeContext, block, options, identifier, true);
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
            rollbackInContext(metadata, resumeContext, options, identifier);
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
     * @return unmodifiable map of running contexts
     */
    public Map<UUID, KanalarzContext> runningContexts() {
        return Collections.unmodifiableMap(contexts);
    }

    /**
     * Cancels the context. A {@link KanalarzException.KanalarzContextCancelledException} exception will be thrown
     * the next time a step is attempted to be executed within this context and on every following attempt.
     * A step started event will not be produced.
     * @param contextId context id to cancel
     * @throws IllegalStateException if the context has already been cancelled or is not running
     */
    public static void cancelContext(UUID contextId) {
        Objects.requireNonNull(contextId);
        cancelContext(contextId, KanalarzContext.State.CANCELLED);
    }

    /**
     * Cancels the context. A {@link KanalarzException.KanalarzContextCancelledException} exception will be thrown
     * the next time a step is attempted to be executed within this context and on every following attempt.
     * A step started event will not be produced. A rollback will not be triggered.
     * @param contextId context id to cancel
     * @throws IllegalStateException if the context has already been cancelled or is not running
     */
    public static void cancelContextForceDeferRollback(UUID contextId) {
        Objects.requireNonNull(contextId);
        cancelContext(contextId, KanalarzContext.State.CANCELLED_FORCE_DEFER_ROLLBACK);
    }

    private static void cancelContext(UUID contextId, KanalarzContext.State newState) {
        if (newState == KanalarzContext.State.RUNNING) {
            throw new KanalarzException.KanalarzInternalError("Can't restore a context state back to running!", null);
        }

        var context = contexts.get(contextId);
        if (context == null) {
            throw new IllegalStateException("Context [%s] is not running".formatted(contextId));
        }

        switch (context.state()) {
            case null -> {}
            case RUNNING -> context.moveState(newState);
            case POISONED ->
                throw new IllegalStateException("Context [%s] has been poisoned.".formatted(contextId));
            case CANCELLED,
                 CANCELLED_FORCE_DEFER_ROLLBACK ->
                throw new IllegalStateException("Context [%s] has already been cancelled.".formatted(contextId));
        }
    }

    @NullMarked
    private static class AutoCloseableContext implements AutoCloseable {

        private final KanalarzContext context;

        AutoCloseableContext(
            Map<String, String> metadata,
            @Nullable UUID resumesContext,
            EnumSet<Option> options,
            @Nullable StepReplayer stepReplayer,
            @Nullable String identifier
        ) {
            context = new KanalarzContext(resumesContext, options, identifier, stepReplayer, forkExecutor);
            context.putAllMetadata(metadata);
            kanalarzContextThreadLocal.set(new ContextStack(context, contextStackOrNull()));
            contexts.put(context.id(), context);
        }

        public KanalarzContext context() {
            return Objects.requireNonNull(context);
        }

        @Override
        public void close() {
            context.contextForkExecutor().join();
            kanalarzContextThreadLocal.set(contextStackOrThrow().parents());
            contexts.remove(context.id());
        }
    }

    @NullUnmarked
    public static <T> CompletableFuture<T> forkVirtual(Supplier<T> block) {
        var contextStack = contextStackOrThrow().copyWithNewThreadId();
        return contextStack.context().forkExecutor().supplyAsync(() -> {
            try {
                kanalarzContextThreadLocal.set(contextStack);
                return block.get();
            } finally {
                kanalarzContextThreadLocal.remove();
            }
        });
    }

    public static CompletableFuture<@Nullable Void> forkRunVirtual(Runnable block) {
        return forkVirtual(() -> {
            block.run();
            return null;
        });
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
         * If the same step has been called twice with the same parameters but returned a different result each time,
         * and the pipeline is replaying out of order, it's impossible to tell which value to return first. By default,
         * the pipeline discovers when this occurs and fails the replay immediately before replaying any steps.
         * If this option is enabled, that situation is ignored. Make sure you accept the potential danger if you
         * enable this option.
         */
        ALLOW_UNSAFE_REPLAY_OF_AMBIGUOUS_OUT_OF_ORDER_CALLS,

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

