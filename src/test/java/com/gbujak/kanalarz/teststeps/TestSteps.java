package com.gbujak.kanalarz.teststeps;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsComponent;

@StepsComponent(identifier = "test-steps")
public class TestSteps {

    @Step(identifier = "uppercase-step", fallible = true)
    public String uppercaseStep(String param) {
        return param.toUpperCase();
    }

    @Rollback(forStep = "uppercase-step")
    public void uppercaseRollback() { }

}
