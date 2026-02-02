package com.gbujak.kanalarz.testimplementations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.gbujak.kanalarz.KanalarzSerialization;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@NullMarked
public class TestSerialization implements KanalarzSerialization {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

    @Override
    public String serializeStepCalled(
        List<SerializeParameterInfo> parametersInfo,
        @Nullable SerializeReturnInfo returnInfo
    ) {
        var serialized = mapper.createObjectNode();

        var params = mapper.createArrayNode();
        for (var parameter : parametersInfo) {
            var paramNode = mapper.createObjectNode();
            paramNode.put("name", parameter.name());
            paramNode.set("value", mapper.valueToTree(parameter.value()));
            paramNode.put("secret", parameter.secret());
            paramNode.put("type", parameter.type().getTypeName());
            params.add(paramNode);
        }
        serialized.set("params", params);

        if (returnInfo != null) {
            var result = mapper.createObjectNode();
            result.put("secret", returnInfo.secret());
            result.put("type", returnInfo.type().getTypeName());
            result.put("error", returnInfo.error() != null ? returnInfo.error().toString() : null);
            result.set("value", mapper.valueToTree(returnInfo.value()));
            serialized.set("returnInfo", result);
        }

        return serialized.toString();
    }

    @Override
    public DeserializeParametersResult deserializeParameters(
        String serialized,
        List<DeserializeParameterInfo> parametersInfo,
        Type returnType
    ) {
        JsonNode tree;
        try {
            tree = mapper.readTree(serialized);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        Map<String, Object> parameters = new HashMap<>(parametersInfo.size());

        Object result;
        try {
            result = mapper.readValue(
                tree.get("returnInfo").get("value").toString(),
                mapper.getTypeFactory().constructType(returnType)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        var error = tree.get("returnInfo").get("error").textValue();

        var treeParams = tree.get("params");
        paramLoop: for (var param : parametersInfo) {
            for (var treeParamNode : treeParams) {
                if (treeParamNode.get("name").asText().equals(param.name())) {
                    try {
                        var paramValue = mapper.readValue(
                            treeParamNode.get("value").toString(),
                            mapper.getTypeFactory().constructType(param.type())
                        );
                        parameters.put(param.name(), paramValue);
                        break paramLoop;
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            throw new RuntimeException(
                "could not find value for param %s in %s"
                    .formatted(param.name(), treeParams.toString())
            );
        }

        return new DeserializeParametersResult(
            parameters,
            result,
            error != null ? new RuntimeException(error) : null
        );
    }

    @Override
    public boolean parametersAreEqualIgnoringReturn(String left, String right) {
        try {
            return mapper.readTree(left).get("params")
                .equals(mapper.readTree(right).get("params"));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
