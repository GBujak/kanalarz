package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KanalarzContext {

    private final UUID jobId = UUID.randomUUID();
    private final Kanalarz kanalarz;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    KanalarzContext(Kanalarz kanalarz) {
        this.kanalarz = kanalarz;
    }

    public UUID getJobId() {
        return jobId;
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
    public String getMetadata(String key) {
        return this.metadata.get(key);
    }

    @NonNull
    public Optional<String> getMetadataOpt(String key) {
        return Optional.ofNullable(this.getMetadata(key));
    }

    public String putMetadata(String key, String value) {
        return this.metadata.put(key, value);
    }
}
