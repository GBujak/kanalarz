package com.gbujak.kanalarz

import com.gbujak.kanalarz.KanalarzException.*
import com.gbujak.kanalarz.annotations.*
import org.assertj.core.api.AssertionsForClassTypes
import org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.lang.NonNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.*

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

    @Rollback(forStep = "set-name")
    open fun setNameRollback(
        newName: String?,
        @RollforwardOut originalName: String?,
    ) {
        if (testNameService.name() != newName) {
            throw RuntimeException("Name is no longer $newName, it's now ${testNameService.name()}")
        }
        testNameService.set(originalName)
    }

    @NonNull
    @Step(identifier = "set-name-fallible", fallible = true)
    open fun setNameFallible(name: String?): StepOut<Optional<String>> {
        return StepOut.ofNullable(testNameService.set(name))
    }

    @Rollback(forStep = "set-name-fallible", fallible = true)
    open fun setNameFallibleRollback(
        @NonNull @RollforwardOut oldName: Optional<String>,
        @Arg("name") newName: String?
    ) {
        if (testNameService.name() != newName) {
            throw java.lang.RuntimeException("Name is no longer " + newName)
        }
        testNameService.set(oldName.orElse(null))
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
        assertThat(testNameService.name()).isEqualTo("test")
    }

    @Test
    fun basicRollforward() {
        assertThat(testNameService.name()).isNull()
        kanalarz.newContext().start {
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

        assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).start {
                testSteps.setName(testNewName)
                assertThat(testNameService.name()).isEqualTo(testNewName)
                throw exception
            }
        }
            .isExactlyInstanceOf(KanalarzThrownOutsideOfStepException::class.java)
            .hasCause(exception)

        assertThat(testNameService.name()).isNull()
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2)
    }

    @Test
    fun basicRollbackInsideStep() {
        val testNewName = "test"
        val contextId = UUID.randomUUID()

        assertThat(testNameService.name()).isNull()

        assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).start {
                testSteps.setName(testNewName)
                assertThat(testNameService.name()).isEqualTo(testNewName)
                testSteps.setName(testNewName)
            }
        }
            .isExactlyInstanceOf(KanalarzStepFailedException::class.java)
            .hasCauseExactlyInstanceOf(RuntimeException::class.java)

        assertThat(testNameService.name()).isNull()
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(3)
    }

    @Test
    fun basicResumedContextRollback() {
        val testNewName = "test"
        val testNewName2 = "test2"
        val contextId = UUID.randomUUID()

        assertThat(testNameService.name()).isNull()

        kanalarz.newContext().resumes(contextId).start {
            testSteps.setName(testNewName)
            testSteps.setName(testNewName2)
        }

        assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).start {
                testSteps.setName(testNewName)
                testSteps.setName(testNewName2)
                testSteps.setName(testNewName2)
            }
        }
            .isExactlyInstanceOf(KanalarzStepFailedException::class.java)
            .hasCauseExactlyInstanceOf(RuntimeException::class.java)

        assertThat(testNameService.name()).isNull()
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(9)
    }

    @Test
    fun basicFallibleStepTest() {
        val contextId = UUID.randomUUID()
        val testNewName = "test"

        AssertionsForClassTypes.assertThat(testNameService.name()).isNull()

        kanalarz.newContext().resumes(contextId).start {
            testSteps.setNameFallible(testNewName)
            testSteps.setNameFallible(testNewName)
            null
        }

        assertThat(testNameService.name()).isEqualTo(testNewName)
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2)
    }

    @Test
    fun basicNonFallibleRollbackStepTest() {
        val contextId = UUID.randomUUID()
        val testNewName = "test"
        val testNewName2 = "abc"
        val exception = RuntimeException("test")

        assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).start {
                testSteps.setName(testNewName)
                testNameService.set(testNewName2)
                throw exception
            }
        }
            .isExactlyInstanceOf(KanalarzRollbackStepFailedException::class.java)
            .matches { e ->
                if (e !is KanalarzRollbackStepFailedException) return@matches false
                val rollbackFailedMessage = e.rollbackStepFailedException.message.orEmpty()
                val rollbackStepFailedMessageOk =
                    rollbackFailedMessage.contains("Name is no longer") &&
                        rollbackFailedMessage.contains(testNewName) &&
                        rollbackFailedMessage.contains(testNewName2)
                rollbackStepFailedMessageOk &&
                    e.initialStepFailedException == exception
            }

        assertThat(testNameService.name()).isEqualTo(testNewName2)
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2)
    }

    @Test
    fun basicFallibleRollbackStepTest() {
        val contextId = UUID.randomUUID()
        val testNewName = "test"
        val testNewName2 = "abc"
        val exception = java.lang.RuntimeException("test")

        assertThatThrownBy {
            kanalarz.newContext().resumes(contextId).start {
                testSteps.setNameFallible(testNewName)
                testNameService.set(testNewName2)
                throw exception
            }
        }
            .isExactlyInstanceOf(KanalarzThrownOutsideOfStepException::class.java)
            .hasCause(exception)

        assertThat(testNameService.name()).isEqualTo(testNewName2)
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2)
    }
}
