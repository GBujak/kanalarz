package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@NullMarked
public class KanalarzContext {

    private final UUID id;
    private final EnumSet<Kanalarz.Option> options;
    private final Map<String, String> metadata;
    private final ExecutorService executorService;
    private final ForkExecutor contextForkExecutor;
    @Nullable private final String identifier;
    private final AtomicInteger threadIdGenerator;
    private final int threadId;
    @Nullable private StepReplayer stepReplayer;
    @Nullable private StepStack stepStack = null;
    private State state = State.RUNNING;

    KanalarzContext(
        @Nullable UUID resumesId,
        EnumSet<Kanalarz.Option> options,
        @Nullable String identifier,
        @Nullable StepReplayer stepReplayer,
        ExecutorService executorService
    ) {
        this.id = resumesId != null ? resumesId : UUID.randomUUID();
        this.options = options;
        this.identifier = identifier;
        this.stepReplayer = stepReplayer;
        this.metadata = new ConcurrentHashMap<>();
        this.executorService = executorService;
        this.contextForkExecutor = new ForkExecutor(executorService);
        this.threadIdGenerator = new AtomicInteger(0);
        this.threadId = this.threadIdGenerator.incrementAndGet();
    }

    private KanalarzContext(KanalarzContext other, int threadId) {
        this.id = other.id;
        this.options = other.options;
        this.stepReplayer = other.stepReplayer;
        this.stepStack = other.stepStack;
        this.metadata = other.metadata;
        this.contextForkExecutor = other.contextForkExecutor;
        this.executorService = other.executorService;
        this.identifier = other.identifier;
        this.threadIdGenerator = other.threadIdGenerator;
        this.threadId = threadId;
    }

    KanalarzContext copy() {
        return new KanalarzContext(this, threadId);
    }

    KanalarzContext copyWithNewThreadId() {
        return new KanalarzContext(this, threadIdGenerator.incrementAndGet());
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    public State state() {
        return state;
    }

    void moveState(State newState) {
        state = newState;
    }

    public UUID id() {
        return id;
    }

    public int threadId() {
        return threadId;
    }

    @Nullable
    public String getIdentifier() {
        return identifier;
    }

    /**
     * @return An *unmodifiable* map with the entire context metadata. For performance reasons, the map is not cloned
     * and can change over time. If that is not desirable, you should clone the map immediately before storing.
     */
    public Map<String, String> fullMetadata() {
        return Collections.unmodifiableMap(this.metadata);
    }

    public void putAllMetadata(Map<String, String> metadata) {
        this.metadata.putAll(metadata);
    }

    @Nullable
    public String getMetadata(String key) {
        return this.metadata.get(key);
    }

    public Optional<String> metadataOpt(String key) {
        return Optional.ofNullable(this.getMetadata(key));
    }

    @Nullable
    public String putMetadata(String key, String value) {
        return this.metadata.put(key, value);
    }

    @Nullable
    public String removeMetadata(String key) {
        return this.metadata.remove(key);
    }

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

    public boolean optionEnabled(Kanalarz.Option option) {
        return options.contains(option);
    }

    <T extends @Nullable Object> T withNewStep(Function<StepStack, T> block) {
        stepStack = new StepStack(UUID.randomUUID(), stepStack, new ForkExecutor(this.executorService));
        try {
            return block.apply(stepStack);
        } finally {
            stepStack.forkExecutor().join();
            stepStack = stepStack.parents();
        }
    }

    ForkExecutor contextForkExecutor() {
        return contextForkExecutor;
    }

    ForkExecutor forkExecutor() {
        return Optional.ofNullable(this.stepStack)
            .map(StepStack::forkExecutor)
            .orElse(this.contextForkExecutor);
    }

    public void yield() {
        switch (this.state) {
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

    public enum State {
        RUNNING,
        CANCELLED,
        CANCELLED_FORCE_DEFER_ROLLBACK,
        POISONED,
    }

    public record StepStack(
        UUID current,
        @Nullable StepStack parents,
        ForkExecutor forkExecutor
    ) {

        @Nullable
        UUID parentStepId() {
            return parents == null ? null : parents.current();
        }
    }
}
