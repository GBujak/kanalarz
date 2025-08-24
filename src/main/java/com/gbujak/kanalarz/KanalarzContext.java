package com.gbujak.kanalarz;

import jakarta.annotation.Nonnull;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class KanalarzContext {

    private final UUID id = UUID.randomUUID();
    private final Kanalarz kanalarz;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private final AtomicReference<UUID> runningStepId = new AtomicReference<>();

    KanalarzContext(Kanalarz kanalarz) {
        this.kanalarz = kanalarz;
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

    <T> T withStepId(Function<UUID, T> block) {
        var stepId = UUID.randomUUID();
        if (!runningStepId.compareAndSet(null, stepId)) {
            // TODO: allow to run steps concurrently
            throw new RuntimeException(
                "Step is already running, can't have more that one step in the context running concurrently!"
            );
        }
        try {
            return block.apply(stepId);
        } finally {
            if (!runningStepId.compareAndSet(stepId, null)) {
                // this should never happen unless there's a bug in the library
                // noinspection ThrowFromFinallyBlock
                throw new RuntimeException("Logic error! Step ID has changed during step execution");
            }
        }
    }
}
