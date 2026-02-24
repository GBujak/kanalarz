package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A representation of a Kanalarz context
 */
@NullMarked
public class KanalarzContext {

    private final UUID id;
    private final EnumSet<Kanalarz.Option> options;
    private final Map<String, String> metadata;
    @Nullable private StepReplayer stepReplayer;
    @Nullable private StepStack stepStack = null;
    private final AtomicReference<State> state;
    private final ExecutionContext executionContext;

    KanalarzContext(
        @Nullable UUID resumesId,
        EnumSet<Kanalarz.Option> options,
        @Nullable StepReplayer stepReplayer
    ) {
        this.id = resumesId != null
            ? resumesId
            : Kanalarz.timeOrderedEpochPlus1();
        
        this.options = options;
        this.stepReplayer = stepReplayer;
        this.metadata = new ConcurrentHashMap<>();
        this.state = new AtomicReference<>(State.RUNNING);

        var basePath =
            resumesId != null && stepReplayer != null
                ? stepReplayer.basePathForContextId(resumesId)
                : null;

        this.executionContext =
            Kanalarz.contextStack()
                .map(contextStack -> contextStack.context().subContextExecution(resumesId))
                .orElse(basePath != null ? new ExecutionContext(basePath) : new ExecutionContext());
    }

    private KanalarzContext(KanalarzContext other, ExecutionContext executionContext) {
        this.id = other.id;
        this.options = other.options;
        this.stepReplayer = other.stepReplayer;
        this.stepStack = other.stepStack;
        this.metadata = other.metadata;
        this.state = other.state;
        this.executionContext = executionContext;
    }

    KanalarzContext copy(ExecutionContext executionContext) {
        return new KanalarzContext(this, executionContext);
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    /**
     * Get current context lifecycle state.
     * @return context state
     */
    public State state() {
        return state.get();
    }

    boolean moveState(State expectedState, State newState) {
        return state.compareAndSet(expectedState, newState);
    }

    /**
     * Get context id.
     * @return context id
     */
    public UUID id() {
        return id;
    }

    /**
     * Get a read-only metadata map view.
     * @return An *unmodifiable* map with the entire context metadata. For performance reasons, the map is not cloned
     * and can change over time. If that is not desirable, you should clone the map immediately before storing.
     */
    public Map<String, String> fullMetadata() {
        return Collections.unmodifiableMap(this.metadata);
    }

    /**
     * Merge all entries into context metadata.
     * @param metadata metadata entries to add
     */
    public void putAllMetadata(Map<String, String> metadata) {
        this.metadata.putAll(metadata);
    }

    /**
     * Get metadata value by key.
     * @param key metadata key
     * @return metadata value or null
     */
    @Nullable
    public String getMetadata(String key) {
        return this.metadata.get(key);
    }

    /**
     * Get metadata value by key as optional.
     * @param key metadata key
     * @return metadata value as optional
     */
    public Optional<String> metadataOpt(String key) {
        return Optional.ofNullable(this.getMetadata(key));
    }

    /**
     * Set metadata value by key.
     * @param key metadata key
     * @param value metadata value
     * @return previous value or null
     */
    @Nullable
    public String putMetadata(String key, String value) {
        return this.metadata.put(key, value);
    }

    /**
     * Remove metadata value by key.
     * @param key metadata key
     * @return removed value or null
     */
    @Nullable
    public String removeMetadata(String key) {
        return this.metadata.remove(key);
    }

    /**
     * Get current step stack frame.
     * @return current step stack or null when no step is active
     */
    @Nullable
    public StepStack stepStack() {
        return stepStack;
    }

    @Nullable
    StepReplayer stepReplayer() {
        return stepReplayer;
    }

    void clearStepReplayer() {
        stepReplayer = null;
    }

    /**
     * Check whether an execution option is enabled in this context.
     * @param option option to check
     * @return true if option is enabled
     */
    public boolean optionEnabled(Kanalarz.Option option) {
        return options.contains(option);
    }

    <T extends @Nullable Object> T withNewStep(Function<StepStack, T> block) {
        stepStack = new StepStack(
            Kanalarz.timeOrderedEpochPlus1(),
            stepStack,
            (stepStack != null ? stepStack.executionContext : executionContext).forNestedSteps()
        );
        try {
            return block.apply(stepStack);
        } finally {
            stepStack = stepStack.parents();
        }
    }

    /**
     * Ensure context is in running state, otherwise throw.
     */
    public void yield() {
        switch (state()) {
            case null -> { }
            case RUNNING -> { }
            case CANCELLED ->
                throw new KanalarzException.KanalarzContextCancelledException(false);
            case CANCELLED_FORCE_DEFER_ROLLBACK ->
                throw new KanalarzException.KanalarzContextCancelledException(true);
            case POISONED ->
                throw new KanalarzException.KanalarzContextPoisonedException();
        }
    }

    /**
     * Runtime state of a context.
     */
    public enum State {
        /** Context is actively executing. */
        RUNNING,
        /** Context has been cancelled and rollback behavior follows normal rules. */
        CANCELLED,
        /** Context has been cancelled and rollback is forcibly deferred. */
        CANCELLED_FORCE_DEFER_ROLLBACK,
        /** Context cannot continue because replay encountered a fatal mismatch. */
        POISONED,
    }

    /**
     * Active step frame in the current context.
     * @param current current step id
     * @param parents parent step frame
     * @param executionContext execution context used for nested paths
     */
    public record StepStack(
        UUID current,
        @Nullable StepStack parents,
        ExecutionContext executionContext
    ) { }

    String nextStepExecutionPath() {
        return Optional.ofNullable(stepStack())
            .map(StepStack::parents)
            .map(StepStack::executionContext)
            .orElse(executionContext)
            .nextStepId();
    }

    ExecutionContext subContextExecution(@Nullable UUID subcontextId) {
        return executionContext.spawnSubContext(subcontextId);
    }

    ExecutionContext.ForkJoinExecutionContext forkJoinTaskContext() {
        return executionContext.forkJoinContext();
    }
}
