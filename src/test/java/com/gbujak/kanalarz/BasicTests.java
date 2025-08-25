package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
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

    private String name;

    public String set(String value) {
        if (Objects.equals(name, value)) {
            throw new RuntimeException("Name already equals that");
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
            throw new RuntimeException(
                "Name changed, it's no longer " + newName + ", it's now " + testNameService.name()
            );
        }
        testNameService.set(originalName.orElse(null));
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
    }

    @Test
    void basicRollforward() {
        assertThat(testNameService.name()).isNull();
        kanalarz.newContext().<Void>start(ctx -> {
            assertThat(testSteps.setName("test")).isEqualTo(Optional.empty());
            return null;
        });
        assertThat(testNameService.name()).isEqualTo("test");
    }

    @Test
    void basicRollbackOutsideOfStep() {
        var testNewName = "test";
        var exception = new RuntimeException("test");
        var contextId = UUID.randomUUID();

        assertThat(testNameService.name()).isEqualTo(null);

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).start(ctx -> {
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
            kanalarz.newContext().resumes(contextId).start(ctx -> {
                testSteps.setName(testNewName);
                assertThat(testNameService.name()).isEqualTo(testNewName);
                testSteps.setName(testNewName);
                return null;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
            .hasCauseExactlyInstanceOf(RuntimeException.class);

        assertThat(testNameService.name()).isNull();
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(3);
    }

    @Test
    void basicResumedContextRollback() {
        var testNewName = "test";
        var testNewName2 = "test2";
        var contextId = UUID.randomUUID();

        assertThat(testNameService.name()).isNull();

        kanalarz.newContext().resumes(contextId).start(ctx -> {
            testSteps.setName(testNewName);
            testSteps.setName(testNewName2);
            return null;
        });

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).start(ctx -> {
                testSteps.setName(testNewName);
                testSteps.setName(testNewName2);
                testSteps.setName(testNewName2);
                return null;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
            .hasCauseExactlyInstanceOf(RuntimeException.class);

        assertThat(testNameService.name()).isNull();
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(9);
    }
}
