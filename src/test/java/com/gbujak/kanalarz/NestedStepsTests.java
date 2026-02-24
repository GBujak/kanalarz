package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class TestNestedStepsService {

    private List<String> values = new ArrayList<>();

    public List<String> values() {
        return Collections.unmodifiableList(values);
    }

    public List<String> add(String value) {
        values.add(value);
        return Collections.unmodifiableList(values);
    }

    public void remove(String value) {
        if (!values.getLast().equals(value)) {
            throw new RuntimeException("bad value: got " + value + " expected " + values.getLast());
        }
        values.removeLast();
    }

    public void clear() {
        values.clear();
    }
}

@Component
@StepsHolder("nested-steps")
class TestNestedSteps {

    private TestNestedStepsService service;
    private TestNestedSteps self;

    public TestNestedSteps(TestNestedStepsService service, @Lazy TestNestedSteps self) {
        this.service = service;
        this.self = self;
    }

    @Step("add")
    public List<String> add(String message) {
        return service.add(message);
    }

    @Rollback("add")
    public void remove(String message) {
        service.remove(message);
    }

    @Step("add-all")
    public List<String> addAll(List<String> values) {
        List<String> result = null;
        for (var value : values) {
            result = self.add(value);
        }
        return result;
    }

    @Step("add-all-twice")
    public List<String> addAllTwice(List<String> values) {
        self.addAll(values);
        if (testException != null) {
            var tmp = testException;
            testException = null;
            throw tmp;
        }
        return self.addAll(values);
    }

    private RuntimeException testException = null;

    public void throwOnNextAddAllTwice(RuntimeException exception) {
        testException = exception;
    }

    @Step("nested-with-rollback")
    public List<String> addAllAndExtraNestedWithRollback(List<String> values) {
        List<String> result = null;
        service.add("extra");
        for (var value : values) {
            result = self.add(value);
        }
        return result;
    }

    @Rollback("nested-with-rollback")
    public void addAllAndExtraNestedWithRollbackRollback() {
        service.remove("extra");
    }
}

@SpringBootTest
public class NestedStepsTests {
    @Autowired private Kanalarz kanalarz;
    @Autowired private KanalarzPersistence persistence;
    @Autowired private TestNestedStepsService service;
    @Autowired private TestNestedSteps steps;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void nestedStep() {
        UUID contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
            assertThat(steps.addAll(List.of("test-2", "test-3", "test-4")))
                .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4"));
            assertThat(steps.add("test-5"))
                .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5"));
        });

        assertThat(service.values()).isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5"));

        System.out.println(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId).stream().map(KanalarzPersistence.StepExecutedInfo::executionPath).toList());
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(6);
    }

    @Test
    void doubleNestedStep() {
        UUID contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
            assertThat(steps.addAll(List.of("test-2", "test-3"))).isEqualTo(List.of("test-1", "test-2", "test-3"));


            assertThat(steps.addAllTwice(List.of("test-4", "test-5")))
                .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5"));

            assertThat(steps.add("test-6"))
                .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5", "test-6"));
        });

        assertThat(service.values())
            .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5", "test-6"));

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(12);
    }

    @Test
    void doubleNestedStepRollback() {
        UUID contextId = UUID.randomUUID();
        var expected = List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5", "test-6");
        RuntimeException exception = new RuntimeException("oops");

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.addAll(List.of("test-2", "test-3"))).isEqualTo(List.of("test-1", "test-2", "test-3"));


                assertThat(steps.addAllTwice(List.of("test-4", "test-5")))
                    .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5"));

                assertThat(steps.add("test-6")).isEqualTo(expected);

                throw exception;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCause(exception);

        assertThat(service.values()).isEmpty();

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(12 + expected.size());
    }

    @Test
    void doubleNestedStepRollbackThrowsInsideStep() {
        UUID contextId = UUID.randomUUID();
        RuntimeException testException = new RuntimeException("test");

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.addAll(List.of("test-2", "test-3"))).isEqualTo(List.of("test-1", "test-2", "test-3"));


                assertThat(steps.addAllTwice(List.of("test-4", "test-5")))
                    .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5"));

                assertThat(steps.add("test-6"))
                    .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5", "test-6"));

                steps.throwOnNextAddAllTwice(testException);
                steps.addAllTwice(List.of("test-7", "test-8"));
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
            .hasCause(testException);

        assertThat(service.values()).isEmpty();

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(26);
    }

    @Test
    void doubleNestedStepResumeReplay() {
        UUID contextId = UUID.randomUUID();
        RuntimeException testException = new RuntimeException("test");

        Consumer<KanalarzContext> job = ctx -> {
            assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));

            assertThat(steps.addAll(List.of("test-2", "test-3")))
                .isEqualTo(List.of("test-1", "test-2", "test-3"));

            steps.addAllTwice(List.of("test-4", "test-5"));
        };

        steps.throwOnNextAddAllTwice(testException);
        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).option(Kanalarz.Option.DEFER_ROLLBACK).consume(job)
        );

        assertThat(service.values())
            .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5"));

        kanalarz.newContext().resumes(contextId).consumeResumeReplay(job);

        assertThat(service.values())
            .isEqualTo(List.of("test-1", "test-2", "test-3", "test-4", "test-5", "test-4", "test-5"));
    }

    @Test
    void shouldRollbackNestedStepWithRollback() {
        RuntimeException exception = new RuntimeException("");

        assertThatThrownBy(() ->
            kanalarz.newContext().consume(ctx -> {
                assertThat(steps.addAllAndExtraNestedWithRollback(List.of("test-1", "test-2", "test-3")))
                    .isEqualTo(List.of("extra", "test-1", "test-2", "test-3"));

                throw exception;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCause(exception);

        assertThat(service.values()).isEmpty();
    }
}
