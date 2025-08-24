package com.gbujak.kanalarz;

import com.gbujak.kanalarz.teststeps.TestSteps;
import com.gbujak.kanalarz.teststeps.TestStepsKotlin;
import com.gbujak.kanalarz.teststeps.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
public class KanalarzTests {

    @Autowired private TestSteps testSteps;
    @Autowired private TestStepsKotlin testStepsKotlin;
    @Autowired private Kanalarz kanalarz;

    @Test
    void stepsCalled() {
        var testUser = new TestUser("Norek");

        try {
            kanalarz.inContext(ctx -> {
                testSteps.uppercaseStep(testUser);
                assertThat(testUser.name()).isEqualTo("NOREK");
                throw new RuntimeException("dupa");
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        assertThat(testUser.name()).isEqualTo("Norek");
    }

    @Test
    void kotlinStepsCalled() {
        testStepsKotlin.uppercaseStep("dupa");
    }
}
