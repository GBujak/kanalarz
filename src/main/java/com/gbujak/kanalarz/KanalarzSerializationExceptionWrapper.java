package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.List;

@NullMarked
class KanalarzSerializationExceptionWrapper implements KanalarzSerialization {

    private final KanalarzSerialization serialization;

    KanalarzSerializationExceptionWrapper(KanalarzSerialization serialization) {
        this.serialization = serialization;
    }

    @Override
    public String serializeStepCalled(
        List<SerializeParameterInfo> parametersInfo,
        @Nullable SerializeReturnInfo returnInfo
    ) {
        try {
            return serialization.serializeStepCalled(parametersInfo, returnInfo);
        } catch (RuntimeException e) {
            throw new KanalarzException.KanalarzSerializationException(e);
        }
    }

    @Override
    public DeserializeParametersResult deserializeParameters(
        String serialized,
        List<DeserializeParameterInfo> parametersInfo,
        Type returnType
    ) {
        try {
            return serialization.deserializeParameters(serialized, parametersInfo, returnType);
        } catch (RuntimeException e) {
            throw new KanalarzException.KanalarzSerializationException(e);
        }
    }

    @Override
    public boolean parametersAreEqualIgnoringReturn(String left, String right) {
        try {
            return serialization.parametersAreEqualIgnoringReturn(left, right);
        } catch (RuntimeException e) {
            throw new KanalarzException.KanalarzSerializationException(e);
        }
    }
}
