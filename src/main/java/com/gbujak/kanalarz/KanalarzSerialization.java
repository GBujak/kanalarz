package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public interface KanalarzSerialization<T> {

    record SerializeParameterInfo(
        @NonNull String name,
        @NonNull Type type,
        @Nullable Object value,
        boolean secret
    ) {}
    record SerializeReturnInfo(
        @NonNull Type type,
        @Nullable Object value,
        @Nullable Throwable error,
        boolean secret
    ) {}
    @NonNull
    T serializeStepCalled(
        @NonNull List<SerializeParameterInfo> parametersInfo,
        @Nullable SerializeReturnInfo returnInfo
    );

    record DeserializeParameterInfo(
        @NonNull String name,
        @NonNull Type type
    ) {}
    record DeserializeParametersResult(
        @NonNull Map<String, Object> parameters,
        @Nullable Object executionResult,
        @Nullable Throwable executionError
    ) {}
    @NonNull
    DeserializeParametersResult deserializeParameters(
        @NonNull T serialized,
        @NonNull List<DeserializeParameterInfo> parametersInfo,
        @NonNull Type returnType
    );

    boolean parametersAreEqualIgnoringReturn(T left, T right);
}
