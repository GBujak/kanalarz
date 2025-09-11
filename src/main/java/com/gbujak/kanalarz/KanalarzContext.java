package com.gbujak.kanalarz;

import jakarta.annotation.Nonnull;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class KanalarzContext {

    private final UUID id;
    private final EnumSet<Kanalarz.Option> options;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    public record StepStack(@NonNull UUID current, @Nullable StepStack parents) {

        @Nullable
        UUID parentStepId() {
            return parents == null ? null : parents.current();
        }
    }
    private final ThreadLocal<StepStack> stepStack = new InheritableThreadLocal<>();

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

    @NonNull
    public UUID getId() {
        return id;
    }

    /**
     * @return An *unmodifiable* map with the entire context metadata. For performance reasons, the map is not cloned
     * and can change over time. If that is not desirable, you should clone the map immediately before storing.
     */
    @NonNull
    public Map<String, String> fullMetadata() {
        return Collections.unmodifiableMap(this.metadata);
    }

    public void putAllMetadata(@NonNull Map<String, String> metadata) {
        this.metadata.putAll(metadata);
    }

    @Nullable
    public String getMetadata(@NonNull String key) {
        return this.metadata.get(key);
    }

    @NonNull
    public Optional<String> getMetadataOpt(@Nonnull String key) {
        return Optional.ofNullable(this.getMetadata(key));
    }

    @Nullable
    public String putMetadata(@NonNull String key, @Nonnull String value) {
        return this.metadata.put(key, value);
    }

    @Nullable
    public String removeMetadata(@NonNull String key) {
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

    <T> T withStepId(Function<StepStack, T> block) {
        stepStack.set(new StepStack(UUID.randomUUID(), stepStack.get()));
        try {
            return block.apply(stepStack.get());
        } finally {
            stepStack.set(stepStack.get().parents());
        }
    }
}
