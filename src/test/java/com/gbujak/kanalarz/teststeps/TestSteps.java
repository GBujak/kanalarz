package com.gbujak.kanalarz.teststeps;

import com.gbujak.kanalarz.StepOut;
import com.gbujak.kanalarz.annotations.*;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@StepsHolder(identifier = "test-steps")
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class TestSteps {

    @NonNull
    @Step(identifier = "uppercase-step", fallible = true)
    public StepOut<String> uppercaseStep(TestUser testUser) {
        var oldName = testUser.name();
        testUser.setName(testUser.name().toUpperCase());
        return StepOut.of(oldName);
    }

    @Rollback(forStep = "uppercase-step")
    public void uppercaseRollback(TestUser testUser, @NonNull @RollforwardOut String oldName) {
        testUser.setName(oldName);
    }

    @Step(identifier = "test-generic")
    public void testGeneric(@Secret @Arg("testNamesArgName") Map<String, List<String>> names) {

    }
}
