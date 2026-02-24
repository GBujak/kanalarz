package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
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

interface InterfaceOnlyStepDeclarationsTestInterface {

    @Step("set-interface-only-value")
    String setInterfaceOnlyValue(String value);

    @InterfaceMetaStep
    String setMetaAnnotatedValue(String value);
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Step("set-meta-annotated-value")
@interface InterfaceMetaStep { }

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

@Service
@StepsHolder("interface-only-step-declarations-test-steps")
class InterfaceOnlyStepDeclarationsTestImpl implements InterfaceOnlyStepDeclarationsTestInterface {

    @Autowired private InterfaceStepsHolderTestService service;

    @Override
    public String setInterfaceOnlyValue(String value) {
        var current = service.getValue();
        service.setValue(value);
        return current;
    }

    @Rollback("set-interface-only-value")
    public void rollbackSetInterfaceOnlyValue(@RollforwardOut String originalValue) {
        service.setValue(originalValue);
    }

    @Override
    public String setMetaAnnotatedValue(String value) {
        var current = service.getValue();
        service.setValue(value);
        return current;
    }

    @Rollback("set-meta-annotated-value")
    public void rollbackSetMetaAnnotatedValue(@RollforwardOut String originalValue) {
        service.setValue(originalValue);
    }
}

@SpringBootTest
public class InterfaceStepsHolderTest {

    @Autowired private Kanalarz kanalarz;
    @Autowired private KanalarzPersistence persistence;
    @Autowired private InterfaceStepsHolderTestService service;
    @Autowired private InterfaceStepsHolderTestInterface steps;
    @Autowired private InterfaceOnlyStepDeclarationsTestInterface interfaceOnlySteps;

    @BeforeEach
    void beforeEach() {
        service.setValue("");
    }

    @Test
    void implementationMethodAnnotationsAreIntercepted() {
        var exception = new RuntimeException();
        var contextId = UUID.randomUUID();

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                steps.setValue("testValue");
                throw exception;
            })
        ).hasCause(exception);

        assertThat(service.getValue()).isEqualTo("");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId)).hasSize(2);
    }

    @Test
    void interfaceOnlyAndMetaAnnotatedMethodsAreRegisteredAndIntercepted() {
        var exception = new RuntimeException();
        var contextId = UUID.randomUUID();

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                interfaceOnlySteps.setInterfaceOnlyValue("interface-only");
                interfaceOnlySteps.setMetaAnnotatedValue("meta-annotated");
                throw exception;
            })
        ).hasCause(exception);

        assertThat(service.getValue()).isEqualTo("");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .extracting(KanalarzPersistence.StepExecutedInfo::stepIdentifier)
            .isEqualTo(List.of(
                "interface-only-step-declarations-test-steps:set-interface-only-value",
                "interface-only-step-declarations-test-steps:set-meta-annotated-value",
                "interface-only-step-declarations-test-steps:set-meta-annotated-value:rollback",
                "interface-only-step-declarations-test-steps:set-interface-only-value:rollback"
            ));
    }
}
