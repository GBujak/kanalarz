package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class TestNameService {

    public static class NameServiceNameAlreadyThatValueException extends RuntimeException {}

    private String name;

    public String set(String value) {
        if (Objects.equals(name, value)) {
            throw new NameServiceNameAlreadyThatValueException();
        }
        var tmp = name;
        name = value;
        return tmp;
    }

    public String name() {
        return name;
    }

    public void clear() {
        name = null;
    }
}

@Component
@StepsHolder(identifier = "test-steps")
class TestSteps {

    public static class RollbackStepNameNoLongerTheSameException extends RuntimeException {}

    @Autowired private TestNameService testNameService;

    @NonNull
    @Step(identifier = "set-name")
    public Optional<String> setName(String newName) {
        return Optional.ofNullable(testNameService.set(newName));
    }

    @Rollback(forStep = "set-name")
    public void setNameRollback(
        String newName,
        @NonNull @RollforwardOut Optional<String> originalName
    ) {
        if (!Objects.equals(newName, testNameService.name())) {
            throw new RollbackStepNameNoLongerTheSameException();
        }
        testNameService.set(originalName.orElse(null));
    }

    @NonNull
    @Step(identifier = "set-name-fallible", fallible = true)
    public StepOut<Optional<String>> setNameFallible(String name) {
        return StepOut.ofNullable(testNameService.set(name));
    }

    @Rollback(forStep = "set-name-fallible", fallible = true)
    public void setNameFallibleRollback(
        @NonNull @RollforwardOut Optional<String> oldName,
        @Arg("name") String newName
    ) {
        if (!Objects.equals(testNameService.name(), newName)) {
            throw new RuntimeException("Name is no longer " + newName);
        }
        testNameService.set(oldName.orElse(null));
    }
}

@SpringBootTest
public class BasicTests {

    @Autowired private Kanalarz kanalarz;
    @Autowired private TestSteps testSteps;
    @Autowired private TestNameService testNameService;
    @Autowired private KanalarzPersistence persistence;

    @BeforeEach
    void beforeEach() {
        testNameService.clear();
    }

    @Test
    void stepOutsideOfContext() {
        assertThat(testSteps.setName("test")).isEqualTo(Optional.empty());
        assertThat(testNameService.name()).isEqualTo("test");
    }

    @Test
    void basicRollforward() {
        assertThat(testNameService.name()).isNull();
        kanalarz.newContext().consume(ctx ->
            assertThat(testSteps.setName("test")).isEqualTo(Optional.empty())
        );
        assertThat(testNameService.name()).isEqualTo("test");
    }

    @Test
    void basicRollbackOutsideOfStep() {
        var testNewName = "test";
        var exception = new RuntimeException("test");
        var contextId = UUID.randomUUID();

        assertThat(testNameService.name()).isEqualTo(null);

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                testSteps.setName(testNewName);
                assertThat(testNameService.name()).isEqualTo(testNewName);
                throw exception;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCause(exception);

        assertThat(testNameService.name()).isNull();
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(2);
    }

    @Test
    void basicRollbackInsideStep() {
        var testNewName = "test";
        var contextId = UUID.randomUUID();

        assertThat(testNameService.name()).isNull();

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                testSteps.setName(testNewName);
                assertThat(testNameService.name()).isEqualTo(testNewName);
                testSteps.setName(testNewName);
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
            .hasCauseExactlyInstanceOf(TestNameService.NameServiceNameAlreadyThatValueException.class);

        assertThat(testNameService.name()).isNull();
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(3);
    }

    @Test
    void basicResumedContextRollback() {
        var testNewName = "test";
        var testNewName2 = "test2";
        var contextId = UUID.randomUUID();

        assertThat(testNameService.name()).isNull();

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            testSteps.setName(testNewName);
            testSteps.setName(testNewName2);
        });

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                testSteps.setName(testNewName);
                testSteps.setName(testNewName2);
                testSteps.setName(testNewName2);
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
            .hasCauseExactlyInstanceOf(TestNameService.NameServiceNameAlreadyThatValueException.class);

        assertThat(testNameService.name()).isNull();
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(9);
    }

    @Test
    void basicFallibleStepTest() {
        var contextId = UUID.randomUUID();
        var testNewName = "test";

        assertThat(testNameService.name()).isNull();

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            testSteps.setNameFallible(testNewName);
            testSteps.setNameFallible(testNewName);
        });

        assertThat(testNameService.name()).isEqualTo(testNewName);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2);
    }

    @Test
    void basicNonFallibleRollbackStepTest() {
        var contextId = UUID.randomUUID();
        var testNewName = "test";
        var testNewName2 = "abc";
        var exception = new RuntimeException("test");

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).start(ctx -> {
                testSteps.setName(testNewName);
                testNameService.set(testNewName2);
                throw exception;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzRollbackStepFailedException.class)
            .matches((Throwable e) -> {
                if (e instanceof KanalarzException.KanalarzRollbackStepFailedException rfe) {
                    return
                        rfe.getInitialStepFailedException().equals(exception) &&
                            rfe.getRollbackStepFailedException().getClass()
                                .equals(TestSteps.RollbackStepNameNoLongerTheSameException.class);
                }
                return false;
            });

        assertThat(testNameService.name()).isEqualTo(testNewName2);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2);
    }

    @Test
    void basicFallibleRollbackStepTest() {
        var contextId = UUID.randomUUID();
        var testNewName = "test";
        var testNewName2 = "abc";
        var exception = new RuntimeException("test");

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).start(ctx -> {
                testSteps.setNameFallible(testNewName);
                testNameService.set(testNewName2);
                throw exception;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCause(exception);

        assertThat(testNameService.name()).isEqualTo(testNewName2);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2);
    }

    @Test
    void basicDeferredRollbackTest() {
        var contextId = UUID.randomUUID();
        var testName1 = "test-name-1";
        var testName2 = "test-name-2";

        assertThat(testNameService.name()).isNull();

        assertThatThrownBy(() ->
            kanalarz.newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.DEFER_ROLLBACK)
                .consume(ctx -> {
                    testSteps.setName(testName1);
                    testSteps.setName(testName2);
                    testSteps.setName(testName2);
                })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
            .hasCauseExactlyInstanceOf(TestNameService.NameServiceNameAlreadyThatValueException.class);

        assertThat(testNameService.name()).isEqualTo(testName2);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(3);

        kanalarz.newContext().resumes(contextId).rollbackNow();

        assertThat(testNameService.name()).isNull();
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(5);
    }
}
