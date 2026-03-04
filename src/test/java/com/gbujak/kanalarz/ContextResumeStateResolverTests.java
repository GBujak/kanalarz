package com.gbujak.kanalarz;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextResumeStateResolverTests {

    @Test
    void shouldReturnConsistentBasePathForContextId() {
        var contextId = UUID.randomUUID();
        var basePath = "r.c-" + contextId;
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath(basePath + ".s0"),
            stepWithPath(basePath + ".s1"),
            stepWithPath(basePath + ".s1.s0")
        ));

        assertThat(resolver.resolveBasePath(contextId)).isEqualTo(basePath);
    }

    @Test
    void shouldFailWhenBasePathIsInconsistentAcrossSteps() {
        var contextId = UUID.randomUUID();
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath("r.c-" + contextId + ".s0"),
            stepWithPath("another-root.c-" + contextId + ".s0")
        ));

        assertThatThrownBy(() -> resolver.resolveBasePath(contextId))
            .isExactlyInstanceOf(KanalarzException.KanalarzIllegalUsageException.class)
            .hasMessageContaining("inconsistent root paths");
    }

    @Test
    void shouldIgnoreStepsThatDoNotContainContextIdPath() {
        var contextId = UUID.randomUUID();
        var basePath = "r.c-" + contextId;
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath(basePath + ".s0"),
            stepWithPath("r.s1")
        ));

        assertThat(resolver.resolveBasePath(contextId)).isEqualTo(basePath);
    }

    @Test
    void shouldReturnNullWhenNoStepContainsContextPath() {
        var contextId = UUID.randomUUID();
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath("r.s0"),
            stepWithPath("r.s1")
        ));

        assertThat(resolver.resolveBasePath(contextId)).isNull();
    }

    @Test
    void shouldFailWhenContextIdAppearsTwiceInSingleExecutionPath() {
        var contextId = UUID.randomUUID();
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath("r.c-" + contextId + ".s0.c-" + contextId + ".s1")
        ));

        assertThatThrownBy(() -> resolver.resolveBasePath(contextId))
            .isExactlyInstanceOf(KanalarzException.KanalarzIllegalUsageException.class)
            .hasMessageContaining("appears multiple times");
    }

    @Test
    void shouldReturnBasePathForNestedContextSegment() {
        var contextId = UUID.randomUUID();
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath("r.s0.c-" + contextId + ".s1")
        ));

        assertThat(resolver.resolveBasePath(contextId)).isEqualTo("r.s0.c-" + contextId);
    }

    @Test
    void shouldReturnConsistentBasePathWhenOnlyDescendantStepsExist() {
        var contextId = UUID.randomUUID();
        var otherContextId = UUID.randomUUID();
        var basePath = "r.s0.c-" + contextId;
        var resolver = new ContextResumeStateResolver(List.of(
            stepWithPath(basePath + ".s0"),
            stepWithPath(basePath + ".s1.c-" + otherContextId + ".s0")
        ));

        assertThat(resolver.resolveBasePath(contextId)).isEqualTo(basePath);
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
