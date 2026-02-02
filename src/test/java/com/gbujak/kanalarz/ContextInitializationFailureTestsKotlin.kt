package com.gbujak.kanalarz

import com.gbujak.kanalarz.annotations.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.lang.reflect.Type
import java.util.*
import java.util.stream.Stream

class ContextInitializationFailureTestsKotlin {

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingScenarios")
    fun shouldFailContextInitializationForInvalidDefinitions(
        scenarioName: String,
        scenarioConfig: Class<*>,
        expectedFailureMessageFragment: String,
    ) {
        val startupFailure = catchThrowable {
            AnnotationConfigApplicationContext().use { context ->
                context.register(FailingContextBaseConfig::class.java, scenarioConfig)
                context.refresh()
            }
        }

        assertThat(startupFailure).isNotNull
        assertThat(startupFailure).hasStackTraceContaining(expectedFailureMessageFragment)
    }

    companion object {
        @JvmStatic
        fun failingScenarios(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "final @StepsHolder class",
                    FinalStepsHolderConfig::class.java,
                    "can't use it as a steps container",
                ),
                Arguments.of(
                    "method with both @Step and @Rollback",
                    StepAndRollbackSameMethodConfig::class.java,
                    "can't be a step and a rollback at the same time",
                ),
                Arguments.of(
                    "final method marked as step",
                    FinalStepMethodConfig::class.java,
                    "is final which is not allowed",
                ),
                Arguments.of(
                    "duplicated rollforward identifier",
                    DuplicateRollforwardIdentifierConfig::class.java,
                    "Duplicated step identifier",
                ),
                Arguments.of(
                    "fallible step with non-StepOut return type",
                    FallibleStepWithoutStepOutConfig::class.java,
                    "Fallible steps must return a",
                ),
                Arguments.of(
                    "fallible step with nullable StepOut return type",
                    FallibleStepWithNullableStepOutConfig::class.java,
                    "Fallible steps must be non-nullable",
                ),
                Arguments.of(
                    "rollback without matching rollforward step",
                    RollbackWithoutMatchingRollforwardConfig::class.java,
                    "Could not find step",
                ),
                Arguments.of(
                    "duplicated rollback identifier",
                    DuplicateRollbackIdentifierConfig::class.java,
                    "Duplicated step identifier",
                ),
                Arguments.of(
                    "rollback @RollforwardOut type mismatch",
                    RollforwardOutTypeMismatchConfig::class.java,
                    "don't match",
                ),
                Arguments.of(
                    "rollback @RollforwardOut nullability mismatch",
                    RollforwardOutNullabilityMismatchConfig::class.java,
                    "different nullability",
                ),
                Arguments.of(
                    "rollback missing matching rollforward parameter",
                    RollbackParamMissingConfig::class.java,
                    "Could not find corresponding param",
                ),
                Arguments.of(
                    "rollback parameter type mismatch",
                    RollbackParamTypeMismatchConfig::class.java,
                    "but the types are different",
                ),
                Arguments.of(
                    "rollback parameter nullability mismatch",
                    RollbackParamNullabilityMismatchConfig::class.java,
                    "different nullability",
                ),
                Arguments.of(
                    "rollback-only step identifier collides with regular step",
                    RollbackOnlyIdentifierCollisionConfig::class.java,
                    "Duplicated step identifier",
                ),
                Arguments.of(
                    "rollback-only method returns unsupported type",
                    RollbackOnlyInvalidReturnTypeConfig::class.java,
                    "must return void, Void, or kotlin.Unit",
                ),
                Arguments.of(
                    "step description references missing parameter",
                    StepDescriptionMissingParamConfig::class.java,
                    "Description parameter [missing] has no corresponding step parameter",
                ),
                Arguments.of(
                    "rollback description references missing parameter",
                    RollbackDescriptionMissingParamConfig::class.java,
                    "Description parameter [missing] has no corresponding step parameter",
                ),
                Arguments.of(
                    "rollback-only description references missing parameter",
                    RollbackOnlyDescriptionMissingParamConfig::class.java,
                    "Description parameter [missing] has no corresponding step parameter",
                ),
            )
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KanalarzConfiguration::class)
    internal class FailingContextBaseConfig {
        @Bean
        fun kanalarzSerialization(): KanalarzSerialization = NoopSerialization()

        @Bean
        fun kanalarzPersistence(): KanalarzPersistence = NoopPersistence()
    }

    internal class NoopSerialization : KanalarzSerialization {
        override fun serializeStepCalled(
            parametersInfo: List<KanalarzSerialization.SerializeParameterInfo>,
            returnInfo: KanalarzSerialization.SerializeReturnInfo?,
        ): String = ""

        override fun deserializeParameters(
            serialized: String,
            parametersInfo: List<KanalarzSerialization.DeserializeParameterInfo>,
            returnType: Type,
        ): KanalarzSerialization.DeserializeParametersResult =
            KanalarzSerialization.DeserializeParametersResult(emptyMap(), null, null)

        override fun parametersAreEqualIgnoringReturn(left: String, right: String): Boolean = left == right
    }

    internal class NoopPersistence : KanalarzPersistence {
        override fun stepStarted(stepStartedEvent: KanalarzPersistence.StepStartedEvent) {}

        override fun stepCompleted(stepCompletedEvent: KanalarzPersistence.StepCompletedEvent) {}

        override fun getExecutedStepsInContextInOrderOfExecutionStarted(
            contextId: UUID,
        ): List<KanalarzPersistence.StepExecutedInfo> = mutableListOf()
    }

    @Configuration(proxyBeanMethods = false)
    internal class FinalStepsHolderConfig {
        @Bean
        fun invalidSteps(): FinalStepsHolder = FinalStepsHolder()
    }

    @StepsHolder("final-steps-holder-kotlin")
    class FinalStepsHolder {
        @Step("s")
        fun s(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class StepAndRollbackSameMethodConfig {
        @Bean
        fun invalidSteps(): StepAndRollbackSameMethodSteps = StepAndRollbackSameMethodSteps()
    }

    @StepsHolder("step-and-rollback-same-method-kotlin")
    open class StepAndRollbackSameMethodSteps {
        @Step("s")
        @Rollback("s")
        open fun s(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class FinalStepMethodConfig {
        @Bean
        fun invalidSteps(): FinalStepMethodSteps = FinalStepMethodSteps()
    }

    @StepsHolder("final-step-method-kotlin")
    open class FinalStepMethodSteps {
        @Step("s")
        fun s(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class DuplicateRollforwardIdentifierConfig {
        @Bean
        fun invalidSteps(): DuplicateRollforwardIdentifierSteps = DuplicateRollforwardIdentifierSteps()
    }

    @StepsHolder("duplicate-rollforward-identifier-kotlin")
    open class DuplicateRollforwardIdentifierSteps {
        @Step("same")
        open fun first(): String = ""

        @Step("same")
        open fun second(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class FallibleStepWithoutStepOutConfig {
        @Bean
        fun invalidSteps(): FallibleStepWithoutStepOutSteps = FallibleStepWithoutStepOutSteps()
    }

    @StepsHolder("fallible-without-step-out-kotlin")
    open class FallibleStepWithoutStepOutSteps {
        @Step(value = "s", fallible = true)
        open fun s(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class FallibleStepWithNullableStepOutConfig {
        @Bean
        fun invalidSteps(): FallibleStepWithNullableStepOutSteps = FallibleStepWithNullableStepOutSteps()
    }

    @StepsHolder("fallible-nullable-step-out-kotlin")
    open class FallibleStepWithNullableStepOutSteps {
        @Step(value = "s", fallible = true)
        open fun s(): StepOut<String>? = StepOut.of("ok")
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackWithoutMatchingRollforwardConfig {
        @Bean
        fun invalidSteps(): RollbackWithoutMatchingRollforwardSteps = RollbackWithoutMatchingRollforwardSteps()
    }

    @StepsHolder("rollback-without-rollforward-kotlin")
    open class RollbackWithoutMatchingRollforwardSteps {
        @Rollback("missing")
        open fun rollback() {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class DuplicateRollbackIdentifierConfig {
        @Bean
        fun invalidSteps(): DuplicateRollbackIdentifierSteps = DuplicateRollbackIdentifierSteps()
    }

    @StepsHolder("duplicate-rollback-identifier-kotlin")
    open class DuplicateRollbackIdentifierSteps {
        @Step("s")
        open fun s(): String = ""

        @Rollback("s")
        open fun rollbackFirst() {}

        @Rollback("s")
        open fun rollbackSecond() {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollforwardOutTypeMismatchConfig {
        @Bean
        fun invalidSteps(): RollforwardOutTypeMismatchSteps = RollforwardOutTypeMismatchSteps()
    }

    @StepsHolder("rollforward-out-type-mismatch-kotlin")
    open class RollforwardOutTypeMismatchSteps {
        @Step("s")
        open fun s(): String = ""

        @Rollback("s")
        open fun rollback(@RollforwardOut output: Int) {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollforwardOutNullabilityMismatchConfig {
        @Bean
        fun invalidSteps(): RollforwardOutNullabilityMismatchSteps = RollforwardOutNullabilityMismatchSteps()
    }

    @StepsHolder("rollforward-out-nullability-mismatch-kotlin")
    open class RollforwardOutNullabilityMismatchSteps {
        @Step("s")
        open fun s(): String = ""

        @Rollback("s")
        open fun rollback(@RollforwardOut output: String?) {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackParamMissingConfig {
        @Bean
        fun invalidSteps(): RollbackParamMissingSteps = RollbackParamMissingSteps()
    }

    @StepsHolder("rollback-param-missing-kotlin")
    open class RollbackParamMissingSteps {
        @Step("s")
        open fun s(@Arg("expected") value: String): String = value

        @Rollback("s")
        open fun rollback(@Arg("missing") value: String) {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackParamTypeMismatchConfig {
        @Bean
        fun invalidSteps(): RollbackParamTypeMismatchSteps = RollbackParamTypeMismatchSteps()
    }

    @StepsHolder("rollback-param-type-mismatch-kotlin")
    open class RollbackParamTypeMismatchSteps {
        @Step("s")
        open fun s(@Arg("same") value: String): String = value

        @Rollback("s")
        open fun rollback(@Arg("same") value: Int) {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackParamNullabilityMismatchConfig {
        @Bean
        fun invalidSteps(): RollbackParamNullabilityMismatchSteps = RollbackParamNullabilityMismatchSteps()
    }

    @StepsHolder("rollback-param-nullability-mismatch-kotlin")
    open class RollbackParamNullabilityMismatchSteps {
        @Step("s")
        open fun s(@Arg("same") value: String): String = value

        @Rollback("s")
        open fun rollback(@Arg("same") value: String?) {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackOnlyIdentifierCollisionConfig {
        @Bean
        fun invalidSteps(): RollbackOnlyIdentifierCollisionSteps = RollbackOnlyIdentifierCollisionSteps()
    }

    @StepsHolder("rollback-only-identifier-collision-kotlin")
    open class RollbackOnlyIdentifierCollisionSteps {
        @Step("same")
        open fun s(): String = ""

        @RollbackOnly("same")
        open fun rollbackOnly() {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackOnlyInvalidReturnTypeConfig {
        @Bean
        fun invalidSteps(): RollbackOnlyInvalidReturnTypeSteps = RollbackOnlyInvalidReturnTypeSteps()
    }

    @StepsHolder("rollback-only-invalid-return-type-kotlin")
    open class RollbackOnlyInvalidReturnTypeSteps {
        @RollbackOnly("s")
        open fun rollbackOnly(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class StepDescriptionMissingParamConfig {
        @Bean
        fun invalidSteps(): StepDescriptionMissingParamSteps = StepDescriptionMissingParamSteps()
    }

    @StepsHolder("step-description-missing-param-kotlin")
    open class StepDescriptionMissingParamSteps {
        @Step("s")
        @StepDescription("value {missing}")
        open fun s(): String = ""
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackDescriptionMissingParamConfig {
        @Bean
        fun invalidSteps(): RollbackDescriptionMissingParamSteps = RollbackDescriptionMissingParamSteps()
    }

    @StepsHolder("rollback-description-missing-param-kotlin")
    open class RollbackDescriptionMissingParamSteps {
        @Step("s")
        open fun s(): String = ""

        @Rollback("s")
        @StepDescription("value {missing}")
        open fun rollback() {}
    }

    @Configuration(proxyBeanMethods = false)
    internal class RollbackOnlyDescriptionMissingParamConfig {
        @Bean
        fun invalidSteps(): RollbackOnlyDescriptionMissingParamSteps = RollbackOnlyDescriptionMissingParamSteps()
    }

    @StepsHolder("rollback-only-description-missing-param-kotlin")
    open class RollbackOnlyDescriptionMissingParamSteps {
        @RollbackOnly("s")
        @StepDescription("value {missing}")
        open fun rollbackOnly() {}
    }
}
