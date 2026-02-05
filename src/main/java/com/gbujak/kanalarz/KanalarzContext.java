package com.gbujak.kanalarz;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

@NullMarked
public class KanalarzContext {

    private final UUID id;
    private final EnumSet<Kanalarz.Option> options;
    private final Map<String, String> metadata;
    private final ContextForkExecutor contextForkExecutor;
    @Nullable private StepReplayer stepReplayer;
    @Nullable private StepStack stepStack = null;
    @Nullable private final String identifier;

    public record StepStack(
        UUID current,
        @Nullable StepStack parents
    ) {

        @Nullable
        UUID parentStepId() {
            return parents == null ? null : parents.current();
        }
    }

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
        this.contextForkExecutor = new ContextForkExecutor(executorService);
    }

    private KanalarzContext(KanalarzContext other) {
        this.id = other.id;
        this.options = other.options;
        this.stepReplayer = other.stepReplayer;
        this.stepStack = other.stepStack;
        this.metadata = other.metadata;
        this.contextForkExecutor = other.contextForkExecutor;
        this.identifier = other.identifier;
    }

    KanalarzContext copy() {
        return new KanalarzContext(this);
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    public UUID getId() {
        return id;
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


    public Optional<String> getMetadataOpt(String key) {
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
        stepStack = new StepStack(UUID.randomUUID(), stepStack);
        try {
            return block.apply(stepStack);
        } finally {
            stepStack = stepStack.parents();
        }
    }

    ContextForkExecutor forkExecutor() {
        return this.contextForkExecutor;
    }
}
