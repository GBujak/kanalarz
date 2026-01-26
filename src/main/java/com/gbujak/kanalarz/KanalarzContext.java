package com.gbujak.kanalarz;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@NullMarked
public class KanalarzContext {

    private final UUID id;
    private final EnumSet<Kanalarz.Option> options;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    public record StepStack(UUID current, @Nullable StepStack parents) {

        @Nullable
        UUID parentStepId() {
            return parents == null ? null : parents.current();
        }
    }
    private final ThreadLocal<@Nullable StepStack> stepStack = new InheritableThreadLocal<>();

    @Nullable
    private KanalarzStepReplayer stepReplayer;

    KanalarzContext(
        @Nullable UUID resumesId,
        EnumSet<Kanalarz.Option> options,
        @Nullable KanalarzStepReplayer stepReplayer
    ) {
        this.id = resumesId != null ? resumesId : UUID.randomUUID();
        this.options = options;
        this.stepReplayer = stepReplayer;
    }


    public UUID getId() {
        return id;
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
        // Intellij static null analysis doesn't handle this... thinks I'm returning Optional<@Nullable String>
        return (Optional<@NonNull String>) Optional.ofNullable(this.getMetadata(key));
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
        return stepStack.get();
    }

    @Nullable
    KanalarzStepReplayer stepReplayer() {
        return stepReplayer;
    }

    void clearStepReplayer() {
        stepReplayer = null;
    }

    public boolean optionEnabled(Kanalarz.Option option) {
        return options.contains(option);
    }

    <T extends @Nullable Object> T withStepId(Function<StepStack, T> block) {
        stepStack.set(new StepStack(UUID.randomUUID(), stepStack.get()));
        try {
            return block.apply(Objects.requireNonNull(
                stepStack.get(),
                "Logic error! KanalarzContext.withStepId unexpected null!")
            );
        } finally {
            stepStack.set(
                Objects.requireNonNull(
                    stepStack.get(),
                    "Logic error! KanalarzContext.withStepId finally unexpected null!"
                ).parents()
            );
        }
    }
}
