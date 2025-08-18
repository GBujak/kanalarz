package com.gbujak.kanalarz.teststeps;

import com.gbujak.kanalarz.StepOut;
import com.gbujak.kanalarz.annotations.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@StepsHolder(identifier = "test-steps")
public class TestSteps {

    @Step(identifier = "uppercase-step", fallible = true)
    public StepOut<String> uppercaseStep(TestUser testUser) {
        var oldName = testUser.name();
        testUser.setName(testUser.name().toUpperCase());
        return StepOut.ok(oldName);
    }

    @Rollback(forStep = "uppercase-step")
    public void uppercaseRollback(TestUser testUser, @RollforwardOut StepOut<String> oldName) {
        testUser.setName(oldName.getOrThrow());
    }

    @Step(identifier = "test-generic")
    public void testGeneric(@Secret @Arg("testNamesArgName") Map<String, List<String>> names) {

    }
}
