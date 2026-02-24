package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ContextInitializationFailureTests {

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingScenarios")
    void shouldFailContextInitializationForInvalidDefinitions(
        String scenarioName,
        Class<?> scenarioConfig,
        String expectedFailureMessageFragment
    ) {
        var startupFailure = catchThrowable(() -> {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(FailingContextBaseConfig.class, scenarioConfig);
                context.refresh();
            }
        });

        assertThat(startupFailure).isNotNull();
        assertThat(startupFailure).hasStackTraceContaining(expectedFailureMessageFragment);
    }

    static Stream<Arguments> failingScenarios() {
        return Stream.of(
            Arguments.of(
                "final @StepsHolder class",
                FinalStepsHolderConfig.class,
                "can't use it as a steps container"
            ),
            Arguments.of(
                "method with both @Step and @Rollback",
                StepAndRollbackSameMethodConfig.class,
                "can't be a step and a rollback at the same time"
            ),
            Arguments.of(
                "final method marked as step",
                FinalStepMethodConfig.class,
                "is final which is not allowed"
            ),
            Arguments.of(
                "duplicated rollforward identifier",
                DuplicateRollforwardIdentifierConfig.class,
                "Duplicated step identifier"
            ),
            Arguments.of(
                "fallible step with non-StepOut return type",
                FallibleStepWithoutStepOutConfig.class,
                "Fallible steps must return a"
            ),
            Arguments.of(
                "fallible step with nullable StepOut return type",
                FallibleStepWithNullableStepOutConfig.class,
                "Fallible steps must be non-nullable"
            ),
            Arguments.of(
                "rollback without matching rollforward step",
                RollbackWithoutMatchingRollforwardConfig.class,
                "Could not find step"
            ),
            Arguments.of(
                "duplicated rollback identifier",
                DuplicateRollbackIdentifierConfig.class,
                "Duplicated step identifier"
            ),
            Arguments.of(
                "rollback @RollforwardOut type mismatch",
                RollforwardOutTypeMismatchConfig.class,
                "don't match"
            ),
            Arguments.of(
                "rollback @RollforwardOut nullability mismatch",
                RollforwardOutNullabilityMismatchConfig.class,
                "different nullability"
            ),
            Arguments.of(
                "rollback missing matching rollforward parameter",
                RollbackParamMissingConfig.class,
                "Could not find corresponding param"
            ),
            Arguments.of(
                "rollback parameter type mismatch",
                RollbackParamTypeMismatchConfig.class,
                "but the types are different"
            ),
            Arguments.of(
                "rollback parameter nullability mismatch",
                RollbackParamNullabilityMismatchConfig.class,
                "different nullability"
            ),
            Arguments.of(
                "rollback-only step identifier collides with regular step",
                RollbackOnlyIdentifierCollisionConfig.class,
                "Duplicated step identifier"
            ),
            Arguments.of(
                "rollback-only method returns unsupported type",
                RollbackOnlyInvalidReturnTypeConfig.class,
                "must return void, Void, or kotlin.Unit"
            ),
            Arguments.of(
                "step description references missing parameter",
                StepDescriptionMissingParamConfig.class,
                "Description parameter [missing] has no corresponding step parameter"
            ),
            Arguments.of(
                "rollback description references missing parameter",
                RollbackDescriptionMissingParamConfig.class,
                "Description parameter [missing] has no corresponding step parameter"
            ),
            Arguments.of(
                "rollback-only description references missing parameter",
                RollbackOnlyDescriptionMissingParamConfig.class,
                "Description parameter [missing] has no corresponding step parameter"
            )
        );
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KanalarzConfiguration.class)
    static class FailingContextBaseConfig {

        @Bean
        KanalarzSerialization kanalarzSerialization() {
            return new NoopSerialization();
        }

        @Bean
        KanalarzPersistence kanalarzPersistence() {
            return new NoopPersistence();
        }
    }

    static class NoopSerialization implements KanalarzSerialization {
        @Override
        public String serializeStepCalled(
            List<SerializeParameterInfo> parametersInfo,
            @Nullable SerializeReturnInfo returnInfo
        ) {
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
    }

    static class NoopPersistence implements KanalarzPersistence {
        @Override
        public void stepStarted(StepStartedEvent stepStartedEvent) { }

        @Override
        public void stepCompleted(StepCompletedEvent stepCompletedEvent) { }

        @Override
        public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecutionStarted(UUID contextId) {
            return List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FinalStepsHolderConfig {
        @Bean
        FinalStepsHolder invalidSteps() {
            return new FinalStepsHolder();
        }
    }

    @StepsHolder("final-steps-holder")
    static final class FinalStepsHolder {
        @Step("s")
        String s() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StepAndRollbackSameMethodConfig {
        @Bean
        StepAndRollbackSameMethodSteps invalidSteps() {
            return new StepAndRollbackSameMethodSteps();
        }
    }

    @StepsHolder("step-and-rollback-same-method")
    static class StepAndRollbackSameMethodSteps {
        @Step("s")
        @Rollback("s")
        String s() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FinalStepMethodConfig {
        @Bean
        FinalStepMethodSteps invalidSteps() {
            return new FinalStepMethodSteps();
        }
    }

    @StepsHolder("final-step-method")
    static class FinalStepMethodSteps {
        @Step("s")
        final String s() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateRollforwardIdentifierConfig {
        @Bean
        DuplicateRollforwardIdentifierSteps invalidSteps() {
            return new DuplicateRollforwardIdentifierSteps();
        }
    }

    @StepsHolder("duplicate-rollforward-identifier")
    static class DuplicateRollforwardIdentifierSteps {
        @Step("same")
        String first() {
            return "";
        }

        @Step("same")
        String second() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FallibleStepWithoutStepOutConfig {
        @Bean
        FallibleStepWithoutStepOutSteps invalidSteps() {
            return new FallibleStepWithoutStepOutSteps();
        }
    }

    @StepsHolder("fallible-without-step-out")
    static class FallibleStepWithoutStepOutSteps {
        @Step(value = "s", fallible = true)
        String s() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FallibleStepWithNullableStepOutConfig {
        @Bean
        FallibleStepWithNullableStepOutSteps invalidSteps() {
            return new FallibleStepWithNullableStepOutSteps();
        }
    }

    @StepsHolder("fallible-nullable-step-out")
    static class FallibleStepWithNullableStepOutSteps {
        @Step(value = "s", fallible = true)
        StepOut<String> s() {
            return StepOut.of("ok");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackWithoutMatchingRollforwardConfig {
        @Bean
        RollbackWithoutMatchingRollforwardSteps invalidSteps() {
            return new RollbackWithoutMatchingRollforwardSteps();
        }
    }

    @StepsHolder("rollback-without-rollforward")
    static class RollbackWithoutMatchingRollforwardSteps {
        @Rollback("missing")
        void rollback() { }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateRollbackIdentifierConfig {
        @Bean
        DuplicateRollbackIdentifierSteps invalidSteps() {
            return new DuplicateRollbackIdentifierSteps();
        }
    }

    @StepsHolder("duplicate-rollback-identifier")
    static class DuplicateRollbackIdentifierSteps {
        @Step("s")
        String s() {
            return "";
        }

        @Rollback("s")
        void rollbackFirst() { }

        @Rollback("s")
        void rollbackSecond() { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollforwardOutTypeMismatchConfig {
        @Bean
        RollforwardOutTypeMismatchSteps invalidSteps() {
            return new RollforwardOutTypeMismatchSteps();
        }
    }

    @StepsHolder("rollforward-out-type-mismatch")
    static class RollforwardOutTypeMismatchSteps {
        @Step("s")
        String s() {
            return "";
        }

        @Rollback("s")
        void rollback(@RollforwardOut Integer output) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollforwardOutNullabilityMismatchConfig {
        @Bean
        RollforwardOutNullabilityMismatchSteps invalidSteps() {
            return new RollforwardOutNullabilityMismatchSteps();
        }
    }

    @NullMarked
    @StepsHolder("rollforward-out-nullability-mismatch")
    static class RollforwardOutNullabilityMismatchSteps {
        @Step("s")
        String s() {
            return "";
        }

        @Rollback("s")
        void rollback(@RollforwardOut @Nullable String output) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackParamMissingConfig {
        @Bean
        RollbackParamMissingSteps invalidSteps() {
            return new RollbackParamMissingSteps();
        }
    }

    @StepsHolder("rollback-param-missing")
    static class RollbackParamMissingSteps {
        @Step("s")
        String s(@Arg("expected") String value) {
            return value;
        }

        @Rollback("s")
        void rollback(@Arg("missing") String value) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackParamTypeMismatchConfig {
        @Bean
        RollbackParamTypeMismatchSteps invalidSteps() {
            return new RollbackParamTypeMismatchSteps();
        }
    }

    @StepsHolder("rollback-param-type-mismatch")
    static class RollbackParamTypeMismatchSteps {
        @Step("s")
        String s(@Arg("same") String value) {
            return value;
        }

        @Rollback("s")
        void rollback(@Arg("same") Integer value) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackParamNullabilityMismatchConfig {
        @Bean
        RollbackParamNullabilityMismatchSteps invalidSteps() {
            return new RollbackParamNullabilityMismatchSteps();
        }
    }

    @NullMarked
    @StepsHolder("rollback-param-nullability-mismatch")
    static class RollbackParamNullabilityMismatchSteps {
        @Step("s")
        String s(@Arg("same") String value) {
            return value;
        }

        @Rollback("s")
        void rollback(@Arg("same") @Nullable String value) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackOnlyIdentifierCollisionConfig {
        @Bean
        RollbackOnlyIdentifierCollisionSteps invalidSteps() {
            return new RollbackOnlyIdentifierCollisionSteps();
        }
    }

    @StepsHolder("rollback-only-identifier-collision")
    static class RollbackOnlyIdentifierCollisionSteps {
        @Step("same")
        String s() {
            return "";
        }

        @RollbackOnly("same")
        void rollbackOnly() { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackOnlyInvalidReturnTypeConfig {
        @Bean
        RollbackOnlyInvalidReturnTypeSteps invalidSteps() {
            return new RollbackOnlyInvalidReturnTypeSteps();
        }
    }

    @StepsHolder("rollback-only-invalid-return-type")
    static class RollbackOnlyInvalidReturnTypeSteps {
        @RollbackOnly("s")
        String rollbackOnly() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StepDescriptionMissingParamConfig {
        @Bean
        StepDescriptionMissingParamSteps invalidSteps() {
            return new StepDescriptionMissingParamSteps();
        }
    }

    @StepsHolder("step-description-missing-param")
    static class StepDescriptionMissingParamSteps {
        @Step("s")
        @StepDescription("value {missing}")
        String s() {
            return "";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackDescriptionMissingParamConfig {
        @Bean
        RollbackDescriptionMissingParamSteps invalidSteps() {
            return new RollbackDescriptionMissingParamSteps();
        }
    }

    @StepsHolder("rollback-description-missing-param")
    static class RollbackDescriptionMissingParamSteps {
        @Step("s")
        String s() {
            return "";
        }

        @Rollback("s")
        @StepDescription("value {missing}")
        void rollback() { }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackOnlyDescriptionMissingParamConfig {
        @Bean
        RollbackOnlyDescriptionMissingParamSteps invalidSteps() {
            return new RollbackOnlyDescriptionMissingParamSteps();
        }
    }

    @StepsHolder("rollback-only-description-missing-param")
    static class RollbackOnlyDescriptionMissingParamSteps {
        @RollbackOnly("s")
        @StepDescription("value {missing}")
        void rollbackOnly() { }
    }
}
