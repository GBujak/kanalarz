package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class ResumeReplayTestService {

    private final List<String> messages = new ArrayList<>();
    private int value = 0;

    public void add(int i, String message) {
        messages.add(message);
        value += i;
    }

    public String sub(int i, String message) {
        var removed = messages.remove(message);
        value -= i;
        return removed ? message : null;
    }

    public List<String> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public int getValue() {
        return value;
    }

    public void clear() {
        messages.clear();
        value = 0;
    }
}

@Component
@StepsHolder(identifier = "resume-replay-test")
class ResumeReplayTestSteps {

    @Autowired private ResumeReplayTestService service;

    @Step(identifier = "add")
    public void add(int i, String message) {
        service.add(i, message);
    }

    @Step(identifier = "sub")
    public String sub(int i, String message) {
        return service.sub(i, message);
    }
}

@SpringBootTest
public class ResumeReplayTests {

    @Autowired private Kanalarz kanalarz;
    @Autowired private KanalarzPersistence persistence;
    @Autowired private ResumeReplayTestService service;
    @Autowired private ResumeReplayTestSteps steps;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void test() {
        UUID contextId = UUID.randomUUID();
        var exception = new RuntimeException();
        assertThatThrownBy(() ->
            kanalarz.newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.DEFER_ROLLBACK)
                .consume(ctx -> {
                    steps.add(1, "test-1");
                    steps.add(2, "test-2");
                    throw exception;
                })
        ).hasCause(exception);

        assertThat(service.getMessages()).isEqualTo(List.of("test-1", "test-2"));
        assertThat(service.getValue()).isEqualTo(3);

        kanalarz.newContext()
            .resumes(contextId)
            .consumeResumeReplay(ctx -> {
                steps.add(1, "test-1");
                steps.add(2, "test-2");
                steps.add(3, "test-3");
            });

        assertThat(service.getMessages()).isEqualTo(List.of("test-1", "test-2", "test-3"));
        assertThat(service.getValue()).isEqualTo(6);
    }
}
