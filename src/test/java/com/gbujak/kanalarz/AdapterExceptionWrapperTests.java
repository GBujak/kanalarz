package com.gbujak.kanalarz;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterExceptionWrapperTests {

    @Test
    void shouldWrapSerializationExceptionFromSerialize() {
        KanalarzSerialization serialization = new KanalarzSerialization() {
            @Override
            public String serializeStepCalled(
                List<SerializeParameterInfo> parametersInfo,
                SerializeReturnInfo returnInfo
            ) {
                throw new RuntimeException("serialize-failed");
            }

            @Override
            public DeserializeParametersResult deserializeParameters(
                String serialized,
                List<DeserializeParameterInfo> parametersInfo,
                Type returnType
            ) {
                return new DeserializeParametersResult(Map.of(), null, null);
            }

            @Override
            public boolean parametersAreEqualIgnoringReturn(String left, String right) {
                return left.equals(right);
            }
        };

        var wrapped = new KanalarzSerializationExceptionWrapper(serialization);

        assertThatThrownBy(() -> wrapped.serializeStepCalled(List.of(), null))
            .isExactlyInstanceOf(KanalarzException.KanalarzSerializationException.class)
            .hasCauseExactlyInstanceOf(RuntimeException.class)
            .hasMessageContaining("Provided serialization bean threw an exception");
    }

    @Test
    void shouldWrapSerializationExceptionFromDeserialize() {
        KanalarzSerialization serialization = new KanalarzSerialization() {
            @Override
            public String serializeStepCalled(
                List<SerializeParameterInfo> parametersInfo,
                SerializeReturnInfo returnInfo
            ) {
                return "";
            }

            @Override
            public DeserializeParametersResult deserializeParameters(
                String serialized,
                List<DeserializeParameterInfo> parametersInfo,
                Type returnType
            ) {
                throw new RuntimeException("deserialize-failed");
            }

            @Override
            public boolean parametersAreEqualIgnoringReturn(String left, String right) {
                return left.equals(right);
            }
        };

        var wrapped = new KanalarzSerializationExceptionWrapper(serialization);

        assertThatThrownBy(() -> wrapped.deserializeParameters("", List.of(), String.class))
            .isExactlyInstanceOf(KanalarzException.KanalarzSerializationException.class)
            .hasCauseExactlyInstanceOf(RuntimeException.class)
            .hasMessageContaining("Provided serialization bean threw an exception");
    }

    @Test
    void shouldWrapPersistenceExceptionFromRead() {
        KanalarzPersistence persistence = new KanalarzPersistence() {
            @Override
            public void stepStarted(StepStartedEvent stepStartedEvent) { }

            @Override
            public void stepCompleted(StepCompletedEvent stepCompletedEvent) { }

            @Override
            public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecutionStarted(UUID contextId) {
                throw new RuntimeException("read-failed");
            }
        };

        var wrapped = new KanalarzPersistenceExceptionWrapper(persistence);

        assertThatThrownBy(() -> wrapped.getExecutedStepsInContextInOrderOfExecutionStarted(UUID.randomUUID()))
            .isExactlyInstanceOf(KanalarzException.KanalarzPersistenceException.class)
            .hasCauseExactlyInstanceOf(RuntimeException.class)
            .hasMessageContaining("Provided persistence bean threw an exception");
    }

    @Test
    void shouldWrapPersistenceExceptionFromWrite() {
        KanalarzPersistence persistence = new KanalarzPersistence() {
            @Override
            public void stepStarted(StepStartedEvent stepStartedEvent) {
                throw new RuntimeException("write-failed");
            }

            @Override
            public void stepCompleted(StepCompletedEvent stepCompletedEvent) { }

            @Override
            public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecutionStarted(UUID contextId) {
                return List.of();
            }
        };

        var wrapped = new KanalarzPersistenceExceptionWrapper(persistence);
        var contextId = UUID.randomUUID();
        var stepId = UUID.randomUUID();
        var stepStarted = new KanalarzPersistence.StepStartedEvent(
            List.of(contextId),
            stepId,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            "id",
            null,
            "",
            false,
            false,
            "r.s0"
        );

        assertThatThrownBy(() -> wrapped.stepStarted(stepStarted))
            .isExactlyInstanceOf(KanalarzException.KanalarzPersistenceException.class)
            .hasCauseExactlyInstanceOf(RuntimeException.class)
            .hasMessageContaining("Provided persistence bean threw an exception");
    }
}
