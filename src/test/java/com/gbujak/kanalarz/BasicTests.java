package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@Service
class TestNameService {

    private String name;

    public String setAndGet(String value) {
        if (Objects.equals(name, value)) {
            throw new RuntimeException("Name already equals that");
        }
        var tmp = name;
        name = value;
        return tmp;
    }

    public String name() {
        return name;
    }
}

@Component
@StepsHolder(identifier = "test-steps")
class TestSteps {

    @Autowired private TestNameService testNameService;

    @NonNull
    @Step(identifier = "set-and-get-name", fallible = false)
    public StepOut<Optional<String>> setAndGetName(String newName) {
        if (newName.equals("test")) {
            throw new RuntimeException("stepfailed");
        }
        return StepOut.ofNullable(testNameService.setAndGet(newName));
    }

    @Rollback(forStep = "set-and-get-name", fallible = false)
    public void setAndGetNameRollback(@NonNull @RollforwardOut Optional<String> test) {
        System.out.println(test);
        throw new RuntimeException("rollback failed test");
    }
}

@SpringBootTest
public class BasicTests {

    @Autowired private Kanalarz kanalarz;
    @Autowired private TestSteps testSteps;

    @Test
    void test() {
        var testNewName = "test";

        StepOut<Optional<String>> result = kanalarz.inContext(ctx -> {
            var newName = testSteps.setAndGetName(testNewName);
            throw new RuntimeException("test");
        });

        assertThat(result.valueOrThrow()).isEqualTo(Optional.empty());
    }
}
