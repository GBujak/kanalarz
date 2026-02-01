package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollbackOnly;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import kotlin.Unit;
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
class RollbackOnlyTestService {

    private final List<String> names = new ArrayList<>();
    private final List<String> failureLogs = new ArrayList<>();

    public void addName(String name) {
        this.names.add(name);
    }

    public void removeName(String name) {
        this.names.remove(name);
    }

    public void saveLogFailure(String log) {
        this.failureLogs.add(log);
    }

    public List<String> names() {
        return Collections.unmodifiableList(this.names);
    }

    public List<String> failureLogs() {
        return Collections.unmodifiableList(this.failureLogs);
    }

    public void clear() {
        this.names.clear();
        this.failureLogs.clear();
    }
}

@Component
@StepsHolder("rollback-only-steps")
class RollbackOnlyTestSteps {

    @Autowired private RollbackOnlyTestService service;

    @RollbackOnly("log-failed-names-add")
    Unit logFailedNamesAdd(List<String> names) {
        service.saveLogFailure("Failed to save names " + names);
        return Unit.INSTANCE;
    }

    @Step("save-name")
    void saveName(String name) {
        service.addName(name);
    }

    @Rollback("save-name")
    void rollbackSaveName(String name) {
        service.removeName(name);
    }
}

@SpringBootTest
public class RollbackOnlyTests {

    @Autowired Kanalarz kanalarz;
    @Autowired KanalarzPersistence kanalarzPersistence;
    @Autowired RollbackOnlyTestSteps steps;
    @Autowired RollbackOnlyTestService service;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void neverRunsOnSuccess() {
        var names = List.of("name-1", "name-2", "name-3");
        kanalarz.newContext().consume((ctx) -> {
            steps.logFailedNamesAdd(names);
            for (var name : names) {
                steps.saveName(name);
            }
        });
        assertThat(service.names()).isEqualTo(names);
        assertThat(service.failureLogs()).isEmpty();
    }

    @Test
    void runsOnFailure() {
        var contextId = UUID.randomUUID();
        var names = List.of("name-1", "name-2", "name-3");
        var exception = new RuntimeException("test");

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume((ctx) -> {
                steps.logFailedNamesAdd(names);
                for (var name : names) {
                    steps.saveName(name);
                }
                throw exception;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCause(exception);

        assertThat(service.names()).isEmpty();
        assertThat(service.failureLogs()).isEqualTo(List.of("Failed to save names " + names));
    }
}
