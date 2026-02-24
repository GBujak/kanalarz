package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Serialization adapter used by Kanalarz to persist step parameters and results.
 * <p>
 * This adapter defines the wire format used by replay and rollback. Implementations must keep behavior stable for
 * already persisted data.
 * <p>
 * The key requirement is consistency:
 * values serialized in {@link #serializeStepCalled(List, SerializeReturnInfo)} must be readable back by
 * {@link #deserializeParameters(String, List, Type)}, and argument comparison in
 * {@link #parametersAreEqualIgnoringReturn(String, String)} must match how arguments were serialized.
 */
@NullMarked
public interface KanalarzSerialization {

    /**
     * Parameter metadata/value passed to serializer.
     * @param name parameter name
     * @param type parameter type
     * @param value parameter value
     * @param secret whether value should be treated as secret
     */
    record SerializeParameterInfo(
        String name,
        Type type,
        @Nullable Object value,
        boolean secret
    ) {}

    /**
     * Return metadata/value passed to serializer.
     * @param type return type
     * @param value return value
     * @param error thrown error for failed execution
     * @param secret whether value should be treated as secret
     */
    record SerializeReturnInfo(
        Type type,
        @Nullable Object value,
        @Nullable Throwable error,
        boolean secret
    ) {}

    /**
     * Serialize step input, and optionally the return payload.
     * <p>
     * Called twice per step lifecycle:
     * <ul>
     *     <li>Before execution: {@code returnInfo == null}, payload contains only call arguments.</li>
     *     <li>After execution: {@code returnInfo != null}, payload contains arguments and result/error.</li>
     * </ul>
     * The second form is used later for rollback and resume replay.
     * @param parametersInfo step parameters
     * @param returnInfo optional return payload
     * @return serialized representation
     */
    String serializeStepCalled(
        List<SerializeParameterInfo> parametersInfo,
        @Nullable SerializeReturnInfo returnInfo
    );

    /**
     * Parameter metadata used for deserialization.
     * @param name parameter name
     * @param type parameter type
     */
    record DeserializeParameterInfo(
        String name,
        Type type
    ) {}

    /**
     * Deserialized step payload.
     * @param parameters deserialized parameter map
     * @param executionResult deserialized return value
     * @param executionError deserialized execution error
     */
    record DeserializeParametersResult(
        Map<String, @Nullable Object> parameters,
        @Nullable Object executionResult,
        @Nullable Throwable executionError
    ) {}

    /**
     * Deserialize previously serialized step payload.
     * <p>
     * Expected input is the serialized payload produced from the completed step call (where {@code returnInfo} was
     * present). Implementations must:
     * <ul>
     *     <li>Return parameter values for all requested names in {@code parametersInfo}.</li>
     *     <li>Deserialize return value using {@code returnType}.</li>
     *     <li>Return execution error when serialized payload represents a failed step.</li>
     * </ul>
     * @param serialized serialized payload
     * @param parametersInfo parameter metadata to deserialize
     * @param returnType return type to deserialize
     * @return deserialized payload
     */
    DeserializeParametersResult deserializeParameters(
        String serialized,
        List<DeserializeParameterInfo> parametersInfo,
        Type returnType
    );

    /**
     * Compare two serialized payloads by parameters only, ignoring return value and error payload.
     * This is used during resume replay argument matching.
     * <p>
     * Replay calls this with:
     * <ul>
     *     <li>left: previously persisted serialized payload for historical execution</li>
     *     <li>right: current invocation payload serialized with {@code returnInfo == null}</li>
     * </ul>
     * Return {@code true} only when call arguments are semantically equivalent for replay purposes.
     * @param left left serialized payload
     * @param right right serialized payload
     * @return true when parameters are equivalent
     */
    boolean parametersAreEqualIgnoringReturn(String left,  String right);
}
