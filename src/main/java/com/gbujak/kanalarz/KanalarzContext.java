package com.gbujak.kanalarz;

import jakarta.annotation.Nonnull;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KanalarzContext {

    private final UUID id = UUID.randomUUID();
    private final Kanalarz kanalarz;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    KanalarzContext(Kanalarz kanalarz) {
        this.kanalarz = kanalarz;
    }

    @NonNull
    public UUID getId() {
        return id;
    }

    @NonNull
    public Map<String, String> fullMetadata() {
        return Collections.unmodifiableMap(this.metadata);
    }

    public void putAllMetadata(@NonNull Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata can't be null");
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
}
