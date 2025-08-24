package com.gbujak.kanalarz;

import com.gbujak.kanalarz.teststeps.TestSteps;
import com.gbujak.kanalarz.teststeps.TestStepsKotlin;
import com.gbujak.kanalarz.teststeps.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class KanalarzTests {

    @Autowired private TestSteps testSteps;
    @Autowired private TestStepsKotlin testStepsKotlin;
    @Autowired private Kanalarz kanalarz;

    @Test
    void stepsCalled() {
        kanalarz.inContext(ctx -> {
            testSteps.uppercaseStep(new TestUser("dupa"));
            return null;
        });
    }

    @Test
    void kotlinStepsCalled() {
        testStepsKotlin.uppercaseStep("dupa");
    }
}
