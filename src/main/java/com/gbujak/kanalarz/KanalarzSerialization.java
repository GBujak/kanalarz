package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

@NullMarked
public interface KanalarzSerialization {

    record SerializeParameterInfo(
        String name,
        Type type,
        @Nullable Object value,
        boolean secret
    ) {}
    record SerializeReturnInfo(
        Type type,
        @Nullable Object value,
        @Nullable Throwable error,
        boolean secret
    ) {}
    String serializeStepCalled(
        List<SerializeParameterInfo> parametersInfo,
        @Nullable SerializeReturnInfo returnInfo
    );

    record DeserializeParameterInfo(
        String name,
        Type type
    ) {}
    record DeserializeParametersResult(
        Map<String, @Nullable Object> parameters,
        @Nullable Object executionResult,
        @Nullable Throwable executionError
    ) {}
    DeserializeParametersResult deserializeParameters(
        String serialized,
        List<DeserializeParameterInfo> parametersInfo,
        Type returnType
    );

    boolean parametersAreEqualIgnoringReturn(String left,  String right);
}
