package com.gbujak.kanalarz;

import com.gbujak.kanalarz.teststeps.TestSteps;
import com.gbujak.kanalarz.teststeps.TestStepsKotlin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class KanalarzTests {

    @Autowired private TestSteps testSteps;
    @Autowired private TestStepsKotlin testStepsKotlin;

    @Test
    void stepsCalled() {
        testSteps.uppercaseStep("dupa");
    }

    @Test
    void kotlinStepsCalled() {
        testStepsKotlin.uppercaseStep("dupa");
    }
}
