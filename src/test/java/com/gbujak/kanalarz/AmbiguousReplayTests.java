package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Component
@StepsHolder("ambiguous-replay-steps")
class AmbiguousReplaySteps {

    private final Iterator<Integer> iter =
        IntStream.range(0, Integer.MAX_VALUE).iterator();

    @Step("ambiguous-returning-step")
    int ambiguousReturningStep() {
        return iter.next();
    }
}

@SpringBootTest
public class AmbiguousReplayTests {

    @Autowired AmbiguousReplaySteps steps;
    @Autowired KanalarzPersistence persistence;
    @Autowired Kanalarz kanalarz;

    @Test
    void shouldThrowOnAmbiguousReplay() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            steps.ambiguousReturningStep();
            steps.ambiguousReturningStep();
        });

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2);

        var called = new AtomicBoolean(false);
        assertThatThrownBy(() ->
            kanalarz.newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.OUT_OF_ORDER_REPLAY)
                .consumeResumeReplay(ctx -> {
                    called.set(true);
                })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzUnsafeAmbiguousOutOfOrderReplayException.class);
        assertThat(called.get()).isFalse();
    }

    @Test
    void shouldNotThrowOnAmbiguousReplayIfConfigured() {
        var contextId = UUID.randomUUID();

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            steps.ambiguousReturningStep();
            steps.ambiguousReturningStep();
        });

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(2);

        kanalarz.newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.OUT_OF_ORDER_REPLAY)
            .option(Kanalarz.Option.ALLOW_UNSAFE_REPLAY_OF_AMBIGUOUS_OUT_OF_ORDER_CALLS)
            .consumeResumeReplay(ctx -> {
                steps.ambiguousReturningStep();
                steps.ambiguousReturningStep();
            });
    }
}
