package com.gbujak.kanalarz.teststeps;

import com.gbujak.kanalarz.annotations.*;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Map;

@StepsComponent(identifier = "test-steps")
public class TestSteps {

    @Step(identifier = "uppercase-step", fallible = true)
    public String uppercaseStep(TestUser testUser) {
        var oldName = testUser.name();
        testUser.setName(testUser.name().toUpperCase());
        return oldName;
    }

    @Rollback(forStep = "uppercase-step")
    public void uppercaseRollback(TestUser testUser, @RollforwardOut String oldName) {
        testUser.setName(oldName);
    }

    @Step(identifier = "test-generic")
    public void testGeneric(@Secret @Arg("testNamesArgName") Map<String, List<String>> names) {

    }

    @NonNull
    @Step(identifier = "Non-null void?")
    public void testNonNull() {

    }
}
