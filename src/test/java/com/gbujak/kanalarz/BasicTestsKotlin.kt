package com.gbujak.kanalarz

import com.gbujak.kanalarz.KanalarzException.KanalarzStepFailedException
import com.gbujak.kanalarz.KanalarzException.KanalarzThrownOutsideOfStepException
import com.gbujak.kanalarz.KanalarzPersistence.StepExecutedInfo
import com.gbujak.kanalarz.annotations.Rollback
import com.gbujak.kanalarz.annotations.RollforwardOut
import com.gbujak.kanalarz.annotations.Step
import com.gbujak.kanalarz.annotations.StepsHolder
import org.assertj.core.api.AssertionsForClassTypes
import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.lang.NonNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.*
import java.util.function.Function

@Service
internal class TestNameServiceKotlin {
    private var name: String? = null

    fun set(value: String?): String? {
        if (name == value) {
            throw RuntimeException("Name already equals that")
        }
        val tmp = name
        name = value
        return tmp
    }

    fun name(): String? = name

    fun clear() {
        name = null
    }
}

@Component
@StepsHolder(identifier = "test-steps-kotlin")
internal open class TestStepsKotlin {
    @Autowired
    private lateinit var testNameService: TestNameServiceKotlin

    @Step(identifier = "set-name")
    open fun setName(newName: String?): String? {
        return testNameService.set(newName)
    }

    @Rollback(forStep = "set-name", fallible = true)
    open fun setNameRollback(@RollforwardOut out: String?) {
        testNameService.set(out)
    }
}

@SpringBootTest
class BasicTestsKotlin {
    @Autowired
    private lateinit var kanalarz: Kanalarz

    @Autowired
    private lateinit var testSteps: TestStepsKotlin

    @Autowired
    private lateinit var testNameService: TestNameServiceKotlin

    @Autowired
    private lateinit var persistence: KanalarzPersistence

    @BeforeEach
    fun beforeEach() {
        testNameService.clear()
    }

    @Test
    fun stepOutsideOfContext() {
        assertThat(testSteps.setName("test")).isNull()
    }

    @Test
    fun basicRollforward() {
        assertThat(testNameService.name()).isNull()
        kanalarz.newContext().run {
            assertThat(testSteps.setName("test")).isNull()
        }
        assertThat(testNameService.name()).isEqualTo("test")
    }

    @Test
    fun basicRollbackOutsideOfStep() {
        val testNewName = "test"
        val exception = RuntimeException("test")
        val contextId = UUID.randomUUID()

        assertThat(testNameService.name()).isEqualTo(null)

        AssertionsForClassTypes.assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).run {
                testSteps.setName(testNewName)
                assertThat(testNameService.name()).isEqualTo(testNewName)
                throw exception
            }
        }
            .isExactlyInstanceOf(KanalarzThrownOutsideOfStepException::class.java)
            .hasCause(exception)

        assertThat(testNameService.name()).isNull()
        AssertionsForInterfaceTypes.assertThat<StepExecutedInfo?>(
            persistence.getExecutedStepsInContextInOrderOfExecution(
                contextId
            )
        ).hasSize(2)
    }

    @Test
    fun basicRollbackInsideStep() {
        val testNewName = "test"
        val contextId = UUID.randomUUID()

        assertThat(testNameService.name()).isNull()

        AssertionsForClassTypes.assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).run {
                testSteps.setName(testNewName)
                assertThat(testNameService.name()).isEqualTo(testNewName)
                testSteps.setName(testNewName)
            }
        }
            .isExactlyInstanceOf(KanalarzStepFailedException::class.java)
            .hasCauseExactlyInstanceOf(RuntimeException::class.java)

        assertThat(testNameService.name()).isNull()
        AssertionsForInterfaceTypes.assertThat<StepExecutedInfo?>(
            persistence.getExecutedStepsInContextInOrderOfExecution(
                contextId
            )
        ).hasSize(3)
    }

    @Test
    fun basicResumedContextRollback() {
        val testNewName = "test"
        val testNewName2 = "test2"
        val contextId = UUID.randomUUID()

        assertThat(testNameService.name()).isNull()

        kanalarz.newContext().resumes(contextId).run {
            testSteps.setName(testNewName)
            testSteps.setName(testNewName2)
        }

        AssertionsForClassTypes.assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).run {
                testSteps.setName(testNewName)
                testSteps.setName(testNewName2)
                testSteps.setName(testNewName2)
            }
        }
            .isExactlyInstanceOf(KanalarzStepFailedException::class.java)
            .hasCauseExactlyInstanceOf(RuntimeException::class.java)

        assertThat(testNameService.name()).isNull()
        AssertionsForInterfaceTypes.assertThat(
            persistence.getExecutedStepsInContextInOrderOfExecution(
                contextId
            )
        ).hasSize(9)
    }
}
