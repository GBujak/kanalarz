package com.gbujak.kanalarz.testimplementations;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.Type;
import java.util.List;

public record TestSerializedStepInfo(
    List<Param> params,
    ReturnValue returnValue
) {

    public record Param(
        @NonNull String name,
        @NonNull Type type,
        @Nullable String serialized,
        boolean secret
    ) { }

    public record ReturnValue(
        @NonNull Type type,
        @Nullable String serialized,
        @Nullable String errorMessage,
        boolean secret
    ) { }
}
