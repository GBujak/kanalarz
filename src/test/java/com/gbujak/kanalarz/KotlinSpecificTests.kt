package com.gbujak.kanalarz

import com.gbujak.kanalarz.annotations.Rollback
import com.gbujak.kanalarz.annotations.RollbackOnly
import com.gbujak.kanalarz.annotations.Step
import com.gbujak.kanalarz.annotations.StepsHolder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.stereotype.Component
import java.util.*

@Component
internal open class KotlinSpecificTestsService {

    private var name: String = ""

    fun setName(name: String) {
        this.name = name
    }

    fun name(): String = name
}

@Component
@StepsHolder("kotlin-specific-tests-steps")
internal open class KotlinSpecificTestsSteps {

    @Autowired private lateinit var service: KotlinSpecificTestsService

    @Step("default-param-step")
    open fun defaultParamStep(name: String = "test"): String {
        val upper = name.uppercase()
        service.setName(upper)
        return upper
    }

    @Rollback("default-param-step")
    open fun defaultParamStepRollback(name: String) {
        service.setName(name.lowercase())
    }

    open var setterStep: String = "initial"
        @Step("setter-step")
        set(value) {
            field = value
        }

    @Rollback("setter-step")
    open fun setterStepRollback(value: String?) {
        setterStep = value!!.reversed()
    }

    @RollbackOnly("rollback-only-kotlin-step")
    open fun rollbackOnly() {
        service.setName("name-from-rollback-only-step")
    }
}

@SpringBootTest
class KotlinSpecificTests {

    @Autowired private lateinit var kanalarz: Kanalarz
    @Autowired private lateinit var steps: KotlinSpecificTestsSteps
    @Autowired private lateinit var service: KotlinSpecificTestsService

    @BeforeEach
    fun beforeEach() {
        service.setName("")
    }

    @Test
    fun rollbackStepWithDefaultParameter() {
        runCatching {
            var result: String
            kanalarz.newContext().start {
                result = steps.defaultParamStep()
                assertThat(service.name()).isEqualTo(result)
                throw RuntimeException()
            }
        }
        assertThat(service.name()).isEqualTo("test")
    }

    @Test
    fun setterStepRollback() {
        runCatching {
            kanalarz.newContext().start {
                steps.setterStep = "abc"
                assertThat(steps.setterStep).isEqualTo("abc")
                throw RuntimeException()
            }
        }
        assertThat(steps.setterStep).isEqualTo("cba")
    }

    @Test
    fun rollbackOnlyStepKotlin() {
        val contextId = UUID.randomUUID()
        val exception = RuntimeException("test")
        kanalarz.newContext().resumes(contextId).consume {
            assertThat(steps.rollbackOnly()).isEqualTo(Unit)
        }
        assertThat(service.name()).isEqualTo("")

        assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).consume {
                throw exception
            }
        }.hasCause(exception)
        assertThat(service.name()).isEqualTo("name-from-rollback-only-step")
    }
}