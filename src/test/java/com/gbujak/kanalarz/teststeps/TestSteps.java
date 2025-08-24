package com.gbujak.kanalarz.teststeps;

import com.gbujak.kanalarz.StepOut;
import com.gbujak.kanalarz.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    public StepOut<Optional<String>> uppercaseStep(TestUser testUser) {
        var oldName = testUser.name();
        testUser.setName(testUser.name().toUpperCase());
        return StepOut.ofNullable(oldName);
    }

    @Rollback(forStep = "uppercase-step")
    public void uppercaseRollback(TestUser testUser, @NonNull @RollforwardOut Optional<String> oldName) {
        testUser.setName(oldName.get());
    }

    @Step(identifier = "test-generic")
    public void testGeneric(@Secret @Arg("testNamesArgName") Map<String, List<String>> names) {

    }
}
