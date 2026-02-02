package com.gbujak.kanalarz;

import com.gbujak.kanalarz.KanalarzPersistence.StepExecutedInfo;
import com.gbujak.kanalarz.StepReplayer.SearchResult;
import com.gbujak.kanalarz.annotations.RollbackOnly;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import com.github.f4b6a3.uuid.UuidCreator;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
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
        this.serialization = new KanalarzSerializationExceptionWrapper(serialization);
        this.persistence = new KanalarzPersistenceExceptionWrapper(persistence);
    }

    /**
     * Thread-local stack of active contexts for the current execution thread.
     * @param context current context
     * @param parents parent stack frame, if any
     */
    public record ContextStack(
        KanalarzContext context,
        @Nullable ContextStack parents
    ) {

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
        MethodInvocation invocation,
        StepsHolder stepsHolder,
        @Nullable Step step,
        @Nullable RollbackOnly rollbackOnly
    ) {

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
                invocation,
                stepsHolder,
                step,
                rollbackOnly,
                contextStack.context()
            )
        );
    }

    @Nullable
    private Object handleInContextMethodExecution(
        MethodInvocation invocation,
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

        var serializeParametersInfo = Utils.makeSerializeParametersInfo(invocation.getArguments(), stepInfo);

        var stepReplayer = context.stepReplayer();
        var serializedParameters = serialization.serializeStepCalled(serializeParametersInfo, null);
        var stepExecutionPath = context.nextStepExecutionPath();

        // Must check from this direction if the replayer is done because forked contexts will drain
        // it but won't .clearStepReplayer() on the original context.
        if (stepReplayer != null && stepReplayer.isDone()) {
            context.clearStepReplayer();
        } else if (stepReplayer != null) {
            var foundStep = stepReplayer.findNextStep(
                stepExecutionPath,
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
            stepExecutionPath
        ));

        Object result = null;
        Throwable error = null;
        boolean unwrappedStepOut = false;
        String resultSerialized;
        try {
            result = rollbackOnly != null
                ? Utils.voidOrUnitValue(stepInfo.returnType)
                : proceedInvocation(invocation);
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
            stepExecutionPath
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

    private Object proceedInvocation(MethodInvocation invocation) throws InvocationTargetException {
        try {
            return invocation.proceed();
        } catch (Throwable throwable) {
            throw new InvocationTargetException(throwable);
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
        if (resumeReplay && resumesContext == null) {
            throw new KanalarzException.KanalarzIllegalUsageException(
                "Resume replay only makes sense when resuming some context! " +
                    "Tried to resume replay with a fresh context!"
            );
        }

        var replayer =
            resumeReplay
                ? createStepReplayer(resumesContext, options)
                : null;

        try (
            var autoCloseableContext =
                new AutoCloseableContext(metadata, resumesContext, options, replayer)
        ) {
            try {

                var result = body.apply(autoCloseableContext.context());
                if (replayer != null) {
                    if (!replayer.isDone()) {
                        throw new KanalarzException
                            .KanalarzNotAllStepsReplayedException(replayer.unreplayedStepsInfo());
                    }
                }
                return result;
            } catch (KanalarzException.KanalarzInternalError e) {
                throw e;
            } catch (KanalarzException.KanalarzStepFailedException e) {
                if (!options.contains(Option.DEFER_ROLLBACK)) {
                    performRollback(autoCloseableContext.context(), e.getInitialStepFailedException(), options);
                }
                throw e;
            } catch (KanalarzException.KanalarzContextCancelledException e) {
                if (!options.contains(Option.DEFER_ROLLBACK) && !e.forceDeferRollback()) {
                    performRollback(autoCloseableContext.context(), e, options);
                }
                throw e;
            } catch (Throwable e) {
                if (!options.contains(Option.DEFER_ROLLBACK)) {
                    performRollback(autoCloseableContext.context(), e, options);
                }
                throw new KanalarzException.KanalarzThrownOutsideOfStepException(e);
            }
        }
    }

    private StepReplayer createStepReplayer(UUID resumesContext, EnumSet<Option> options) {
        var executedSteps = persistence.getExecutedStepsInContextInOrderOfExecutionStarted(resumesContext);
        return new StepReplayer(executedSteps, serialization, stepsRegistry);
    }

    private void rollbackInContext(
        Map<String, String> metadata,
        UUID resumesContext,
        EnumSet<Option> options
    ) {
        try (
            var autoCloseableContext =
                new AutoCloseableContext(metadata, resumesContext, options, null)
        ) {
            performRollback(autoCloseableContext.context(), null, options);
        }
    }

    private void performRollback(
        KanalarzContext context,
        @Nullable Throwable originalError,
        EnumSet<Option> options
    ) {
        var executedSteps = persistence.getExecutedStepsInContextInOrderOfExecutionStarted(context.id());
        var executedRollbacks =
            executedSteps.stream()
                .filter(it -> it.wasRollbackFor().isPresent())
                .collect(
                    Collectors.toMap(
                        it -> it.wasRollbackFor().get(),
                        StepExecutedInfo::failed,
                        (leftFailed, rightFailed) -> (leftFailed && rightFailed)
                    ));
        var stepsToRollback =
            executedSteps.reversed().stream()
                .filter(it -> it.wasRollbackFor().isEmpty())
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
                var executionPath = rollforward.executionPath() + ".r";

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
                    rollback.rollbackMarker,
                    executionPath
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
                    rollback.rollbackMarker,
                    executionPath
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

        KanalarzContextBuilder() { }

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
                newOptions.add(option);
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
         * execution path, step identifier and arguments, the value from the previous execution is returned and
         * the step is not executed.
         * <p>
         * If a previously persisted step failed, the step is executed again instead of replaying the failure.
         * <p>
         * If a new step is attempted before all required persisted steps are replayed, replay fails with
         * {@link KanalarzException.KanalarzNewStepBeforeReplayEndedException}.
         * <p>
         * If the pipeline body finishes while some persisted steps are still unreplayed, replay fails with
         * {@link KanalarzException.KanalarzNotAllStepsReplayedException}.
         * When every step has been replayed, the resume-replay context is dropped and the pipeline behaves like
         * a standard pipeline.
         * <br>
         * <b>MAKE SURE NO SIDE EFFECTS HAPPEN OUTSIDE OF STEPS OR THEY WILL BE EXECUTED AGAIN</b>
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
         * Does the same thing as the startResumeReplay method but doesn't return anything.
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
     * @return unmodifiable map of running contexts
     */
    public Map<UUID, KanalarzContext> runningContexts() {
        return Collections.unmodifiableMap(contexts);
    }

    /**
     * Generates a UUIDv7 that the library uses internally as a utility if you want to have IDs that are
     * consistent with the ones you get from this library. The UUIDs are monotonic and lexicographically
     * sortable and fast to generate, but they have low randomness, so the next step ID can be guessed
     * from the previous one. This kind of ID is generated for steps so you can sort them by the ID.
     * @return generated UUIDv7-compatible id
     */
    public static UUID timeOrderedEpochPlus1() {
        return UuidCreator.getTimeOrderedEpochPlus1();
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

        while (true) {
            switch (context.state()) {
                case null -> { return; }
                case RUNNING -> {
                    if (context.moveState(KanalarzContext.State.RUNNING, newState)) {
                        return;
                    }
                }
                case POISONED ->
                    throw new IllegalStateException("Context [%s] has been poisoned.".formatted(contextId));
                case CANCELLED,
                     CANCELLED_FORCE_DEFER_ROLLBACK ->
                    throw new IllegalStateException("Context [%s] has already been cancelled.".formatted(contextId));
            }
        }
    }

    /**
     * Execute the function concurrently for all elements while preserving Kanalarz context propagation.
     * <p>
     * Must be called inside an active Kanalarz context.
     * <p>
     * Returned values are in the same order as input elements.
     * @param elements items to process
     * @param maxParallelism max number of concurrent tasks; must be >= 1
     * @param function function to execute per element
     * @param <X> input type
     * @param <Y> output type
     * @return list of results in input order
     */
    public static <X, Y> List<Y> forkJoin(List<X> elements, int maxParallelism, Function<X, Y> function) {
        if (maxParallelism < 1) {
            throw new IllegalArgumentException("Illegal max parallelism option: " + maxParallelism);
        }

        List<CompletableFuture<Y>> futures = new ArrayList<>(elements.size());
        var contextStack = contextStackOrThrow();
        var context = contextStack.context;
        var forkJoinExecutionContext = context.forkJoinTaskContext();

        Semaphore semaphore =
            maxParallelism != Integer.MAX_VALUE
                ? new Semaphore(maxParallelism)
                : null;

        for (int i = 0; i < elements.size(); i++) {
            var contextCopy = context.copy(forkJoinExecutionContext.forTask(i));
            var element = elements.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (semaphore != null) semaphore.acquireUninterruptibly();
                try {
                    kanalarzContextThreadLocal.set(new ContextStack(contextCopy, contextStack.parents));
                    return function.apply(element);
                } finally {
                    kanalarzContextThreadLocal.remove();
                    if (semaphore != null) semaphore.release();
                }
            }, forkExecutor));
        }

        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Same as {@link #forkJoin(List, int, Function)} with unlimited parallelism.
     * Must be called inside an active Kanalarz context.
     * @param elements items to process
     * @param function function to execute per element
     * @param <X> input type
     * @param <Y> output type
     * @return list of results in input order
     */
    public static <X, Y> List<Y> forkJoin(List<X> elements, Function<X, Y> function) {
        return forkJoin(elements, Integer.MAX_VALUE, function);
    }

    /**
     * Concurrently consume all elements while preserving Kanalarz context propagation.
     * Must be called inside an active Kanalarz context.
     * @param elements items to process
     * @param maxParallelism max number of concurrent tasks; must be >= 1
     * @param consumer consumer to execute per element
     * @param <X> input type
     */
    public static <X> void forkConsume(List<X> elements, int maxParallelism, Consumer<X> consumer) {
        if (maxParallelism < 1) {
            throw new IllegalArgumentException("Illegal max parallelism option: " + maxParallelism);
        }
        forkJoin(elements, maxParallelism, element -> {
            consumer.accept(element);
            return 0;
        });
    }

    /**
     * Same as {@link #forkConsume(List, int, Consumer)} with unlimited parallelism.
     * Must be called inside an active Kanalarz context.
     * @param elements items to process
     * @param consumer consumer to execute per element
     * @param <X> input type
     */
    public static <X> void forkConsume(List<X> elements, Consumer<X> consumer) {
        forkConsume(elements, Integer.MAX_VALUE, consumer);
    }

    @NullMarked
    private static class AutoCloseableContext implements AutoCloseable {

        private final KanalarzContext context;

        AutoCloseableContext(
            Map<String, String> metadata,
            @Nullable UUID resumesContext,
            EnumSet<Option> options,
            @Nullable StepReplayer stepReplayer
        ) {
            context = new KanalarzContext(
                resumesContext,
                options,
                contextStack()
                    .map(stack -> stack.context.stepReplayer())
                    .orElse(stepReplayer)
            );
            context.putAllMetadata(metadata);
            kanalarzContextThreadLocal.set(new ContextStack(context, contextStackOrNull()));
            contexts.put(context.id(), context);
        }

        public KanalarzContext context() {
            return Objects.requireNonNull(context);
        }

        @Override
        public void close() {
            kanalarzContextThreadLocal.set(contextStackOrThrow().parents());
            contexts.remove(context.id());
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
        ;

        @Nullable Option mustHave = null;

        Option() { }
        Option(Option mustHave) {
            this.mustHave = mustHave;
        }

        private static final List<EnumSet<Option>> incompatible = List.of(
            EnumSet.of(SKIP_FAILED_ROLLBACKS, RETRY_FAILED_ROLLBACKS)
        );
    }
}
