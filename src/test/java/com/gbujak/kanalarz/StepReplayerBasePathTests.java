package com.gbujak.kanalarz;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepReplayerBasePathTests {

    private static final KanalarzSerialization NOOP_SERIALIZATION = new KanalarzSerialization() {
        @Override
        public String serializeStepCalled(List<SerializeParameterInfo> parametersInfo, SerializeReturnInfo returnInfo) {
            return "";
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

    @Test
    void shouldReturnConsistentBasePathForContextId() {
        var contextId = UUID.randomUUID();
        var basePath = "r.c-" + contextId;
        var replayer = new StepReplayer(
            List.of(
                stepWithPath(basePath + ".s0"),
                stepWithPath(basePath + ".s1"),
                stepWithPath(basePath + ".s1.s0")
            ),
            NOOP_SERIALIZATION,
            new KanalarzStepsRegistry()
        );

        assertThat(replayer.basePathForContextId(contextId)).isEqualTo(basePath);
    }

    @Test
    void shouldFailWhenBasePathIsInconsistentAcrossSteps() {
        var contextId = UUID.randomUUID();
        var replayer = new StepReplayer(
            List.of(
                stepWithPath("r.c-" + contextId + ".s0"),
                stepWithPath("another-root.c-" + contextId + ".s0")
            ),
            NOOP_SERIALIZATION,
            new KanalarzStepsRegistry()
        );

        assertThatThrownBy(() -> replayer.basePathForContextId(contextId))
            .isExactlyInstanceOf(KanalarzException.KanalarzIllegalUsageException.class)
            .hasMessageContaining("inconsistent root paths");
    }

    @Test
    void shouldFailWhenSomeStepsDoNotContainContextIdPath() {
        var contextId = UUID.randomUUID();
        var replayer = new StepReplayer(
            List.of(
                stepWithPath("r.c-" + contextId + ".s0"),
                stepWithPath("r.s1")
            ),
            NOOP_SERIALIZATION,
            new KanalarzStepsRegistry()
        );

        assertThatThrownBy(() -> replayer.basePathForContextId(contextId))
            .isExactlyInstanceOf(KanalarzException.KanalarzIllegalUsageException.class)
            .hasMessageContaining("inconsistent root paths");
    }

    @Test
    void shouldReturnNullWhenNoStepContainsContextPath() {
        var contextId = UUID.randomUUID();
        var replayer = new StepReplayer(
            List.of(
                stepWithPath("r.s0"),
                stepWithPath("r.s1")
            ),
            NOOP_SERIALIZATION,
            new KanalarzStepsRegistry()
        );

        assertThat(replayer.basePathForContextId(contextId)).isNull();
    }

    private static KanalarzPersistence.StepExecutedInfo stepWithPath(String executionPath) {
        return new KanalarzPersistence.StepExecutedInfo(
            List.of(UUID.randomUUID()),
            UUID.randomUUID(),
            "test:step",
            "",
            Optional.empty(),
            Optional.empty(),
            false,
            executionPath
        );
    }
}
