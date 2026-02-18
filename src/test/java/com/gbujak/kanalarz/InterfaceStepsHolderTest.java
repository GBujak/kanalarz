package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
@NullMarked
class InterfaceStepsHolderTestService {
    private String value = "";

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}

interface InterfaceStepsHolderTestInterface {
    String setValue(String value);
}

@Service
@StepsHolder("interface-steps-holder-test-steps")
class InterfaceStepsHolderTestImpl implements InterfaceStepsHolderTestInterface {

    @Autowired private InterfaceStepsHolderTestService service;

    @Override
    @Step("set-value")
    public String setValue(String value) {
        var current = service.getValue();
        service.setValue(value);
        return current;
    }

    @Rollback("set-value")
    public void rollbackSetValue(@RollforwardOut String originalValue) {
        service.setValue(originalValue);
    }
}

@SpringBootTest
public class InterfaceStepsHolderTest {

    @Autowired private Kanalarz kanalarz;
    @Autowired private KanalarzPersistence persistence;
    @Autowired private InterfaceStepsHolderTestService service;
    @Autowired private InterfaceStepsHolderTestInterface steps;

    @Test
    void test() {
        var exception = new RuntimeException();
        var contextId = UUID.randomUUID();

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                steps.setValue("testValue");
                throw exception;
            })
        ).hasCause(exception);

        assertThat(service.getValue()).isEqualTo("");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(2);
    }
}
