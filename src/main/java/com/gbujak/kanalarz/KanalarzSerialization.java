package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public interface KanalarzSerialization {

    record SerializeParameterInfo(
        @NonNull String name,
        @NonNull Type type,
        @Nullable Object value,
        boolean secret
    ) {}
    record SerializeReturnInfo(
        @NonNull Type type,
        @Nullable StepOut<?> value,
        boolean secret
    ) {}
    @NonNull
    String serializeStepExecution(
        @NonNull List<SerializeParameterInfo> parametersInfo,
        @NonNull SerializeReturnInfo returnInfo
    );

    record DeserializeParameterInfo(
        @NonNull String name,
        @NonNull Type type
    ) {}
    record DeserializeParametersResult(
        @NonNull Map<String, Object> parameters,
        @Nullable StepOut<?> returnValue
    ) {}
    @NonNull
    DeserializeParametersResult deserializeParameters(
        @NonNull String serialized,
        @NonNull List<DeserializeParameterInfo> parametersInfo,
        @NonNull Type returnType
    );
}
