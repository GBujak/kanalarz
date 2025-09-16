package com.gbujak.kanalarz.testimplementations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.gbujak.kanalarz.KanalarzSerialization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.*;

@Component
public class TestSerialization implements KanalarzSerialization<TestSerializedStepInfo> {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

    @NotNull
    @Override
    public TestSerializedStepInfo serializeStepCalled(
        @NotNull List<SerializeParameterInfo> parametersInfo,
        @Nullable SerializeReturnInfo returnInfo
    ) {
        List<TestSerializedStepInfo.Param> params = new ArrayList<>();
        for (var parameter : parametersInfo) {
            try {
                params.add(new TestSerializedStepInfo.Param(
                    parameter.name(),
                    parameter.type(),
                    mapper.writeValueAsString(parameter.value()),
                    parameter.secret()
                ));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        var returnValue = Optional.ofNullable(returnInfo).map(it -> {
            try {
                return new TestSerializedStepInfo.ReturnValue(
                    it.type(),
                    mapper.writeValueAsString(it.value()),
                    Optional.ofNullable(it.error()).map(Throwable::getMessage).orElse(null),
                    it.secret()
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);

        return new TestSerializedStepInfo(params, returnValue);
    }

    @NotNull
    @Override
    public DeserializeParametersResult deserializeParameters(
        @NotNull TestSerializedStepInfo serialized,
        @NotNull List<DeserializeParameterInfo> parametersInfo,
        @NotNull Type returnType
    ) {
        Map<String, Object> parameters = new HashMap<>(parametersInfo.size());
        Object result = null;

        try {
            if (serialized.returnValue().serialized() != null) {
                result = mapper.readValue(
                    serialized.returnValue().serialized(),
                    mapper.getTypeFactory().constructType(returnType)
                );
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        var error = serialized.returnValue().errorMessage();

        for (var param : serialized.params()) {
            try {
                parameters.put(
                    param.name(),
                    mapper.readValue(
                        param.serialized(),
                        mapper.getTypeFactory().constructType(param.type())
                    )
                );
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return new DeserializeParametersResult(
            parameters,
            result,
            error != null ? new RuntimeException(error) : null
        );
    }

    @Override
    public boolean parametersAreEqualIgnoringReturn(
        TestSerializedStepInfo left,
        TestSerializedStepInfo right
    ) {
        return left.params().stream()
            .sorted(Comparator.comparing(TestSerializedStepInfo.Param::name))
            .toList()
            .equals(
                right.params().stream()
                    .sorted(Comparator.comparing(TestSerializedStepInfo.Param::name))
                    .toList()
            );
    }
}
