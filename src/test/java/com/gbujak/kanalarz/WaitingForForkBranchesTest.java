package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Component
@StepsHolder("waiting-for-fork-branches-test-steps")
class WaitingForForkBranchesTestSteps {

    @Step("test-step")
    void test() { }

    @Step("transform-step")
    int transform(int value) { return value * 10; }
}

@SpringBootTest
public class WaitingForForkBranchesTest {

    @Autowired private Kanalarz kanalarz;
    @Autowired private WaitingForForkBranchesTestSteps steps;

    @Test
    void whenOneForkFinishesEarlyItShouldYieldSemaphoreAndWaitForSiblingsToFinishBeforeThrowing() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                steps.test();
                steps.test();
            });
        });

        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                if (i == 1) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                steps.test();
                steps.test();
                steps.test();
            });
        });
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void withMaxParallelismOneForkWaitingForSiblingsShouldReleaseTheSemaphoreToAvoidDeadlock() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), 1, (i) -> {
                steps.test();
            });
        });

        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            // maxParallelism=1: only one fork runs at a time.
            // The running fork will finish replay and hit a new step — without releasing
            // the semaphore first, the sibling could never run and replay would deadlock.
            Kanalarz.forkConsume(List.of(1, 2), 1, (i) -> {
                steps.test();
                steps.test();
            });
        });
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void manyForksWithMaxParallelismShouldNotDeadlock() {
        var contextId = UUID.randomUUID();
        var elements = IntStream.range(0, 6).boxed().toList();

        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            Kanalarz.forkConsume(elements, 2, (i) -> {
                steps.test();
            });
        });

        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            Kanalarz.forkConsume(elements, 2, (i) -> {
                steps.test();
                steps.test();
            });
        });
    }

    @Test
    void asymmetricReplayStepCountsShouldCoordinateCorrectly() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                steps.test();
                if (i == 1) {
                    steps.test();
                    steps.test();
                }
            });
        });

        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                steps.test();
                if (i == 1) {
                    steps.test();
                    steps.test();
                }
                steps.test();
            });
        });
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void forkJoinWithReturnValuesShouldWorkWithResumeReplayAndMaxParallelism() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            var results = Kanalarz.forkJoin(List.of(1, 2, 3), 2, (i) -> {
                return steps.transform(i);
            });
            assertThat(results).containsExactly(10, 20, 30);
        });

        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            var results = Kanalarz.forkJoin(List.of(1, 2, 3), 2, (i) -> {
                steps.transform(i);
                return steps.transform(i);
            });
            assertThat(results).containsExactly(10, 20, 30);
        });
    }

    @Test
    void multipleResumeReplayCyclesShouldWork() {
        var contextId = UUID.randomUUID();

        // Round 1: initial execution
        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                steps.test();
            });
        });

        // Round 2: replay round 1 + new step
        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                steps.test();
                steps.test();
            });
        });

        // Round 3: replay rounds 1+2 + another new step
        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            Kanalarz.forkConsume(List.of(1, 2), (i) -> {
                steps.test();
                steps.test();
                steps.test();
            });
        });
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void singleElementForkWithMaxParallelismShouldNotDeadlock() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume((ctx) -> {
            Kanalarz.forkConsume(List.of(1), 1, (i) -> {
                steps.test();
            });
        });

        kanalarz.newContext().resumes(contextId).consumeResumeReplay((ctx) -> {
            Kanalarz.forkConsume(List.of(1), 1, (i) -> {
                steps.test();
                steps.test();
            });
        });
    }
}