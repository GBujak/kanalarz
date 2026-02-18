package com.gbujak.kanalarz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Component
class ConcurrentTestService {
    List<Integer> valueHistory = new ArrayList<>();
    int value = 0;
    Lock lock = new ReentrantLock();

    void add(int value) {
        try {
            lock.lock();
            this.value += value;
            this.valueHistory.add(this.value);
        } finally {
            lock.unlock();
        }
    }

    void undoAdd(int value) {
        try {
            lock.lock();
            this.value -= value;
            valueHistory.add(this.value);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        value = 0;
        valueHistory.clear();
    }
}

@Component
@StepsHolder("concurrent-steps")
class ConcurrentTestSteps {

    private ConcurrentTestService service;
    private ConcurrentTestSteps self;

    public ConcurrentTestSteps(
        ConcurrentTestService service,
        @Lazy ConcurrentTestSteps self
    ) {
        this.service = service;
        this.self = self;
    }

    @Step("add")
    void add(int value) {
        service.add(value);
    }

    @Rollback("add")
    void addRollback(int value) {
        service.undoAdd(value);
    }

    @Step("add-all-concurrently")
    void addAllConcurrently(List<Integer> values) {
        List<CompletableFuture<?>> futures = new ArrayList<>(values.size());
        for (var value : values) {
            futures.add(Kanalarz.forkRunVirtual(() -> self.add(value)));
        }
        futures.forEach(CompletableFuture::join);
    }
}

@SpringBootTest
public class ConcurrentTests {

    @Autowired private Kanalarz kanalarz;
    @Autowired private ConcurrentTestSteps steps;
    @Autowired private ConcurrentTestService service;
    @Autowired private KanalarzPersistence persistence;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void concurrentStepsTest() {
        UUID contextId = UUID.randomUUID();
        var sum = new AtomicInteger(0);
        var stepsRan = new AtomicInteger(0);
        var nestedStepsRan = new AtomicInteger(0);

        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            for (int i = 0; i < 100; i++) {
                Kanalarz.forkRunVirtual(() -> {
                    if (Math.random() < .2) {
                        int value = (int) Math.floor(Math.random() * 100);
                        sum.addAndGet(value);
                        steps.add(value);
                        stepsRan.addAndGet(1);
                    } else {
                        var values = IntStream.range(0, 100)
                            .boxed()
                            .map(it -> (int) Math.floor(Math.random() * 10))
                            .peek(sum::addAndGet)
                            .toList();
                        steps.addAllConcurrently(values);
                        stepsRan.addAndGet(values.size());
                        nestedStepsRan.addAndGet(1);
                    }
                });
            }
        });

        assertThat(service.value).isEqualTo(sum.get());
        assertThat(service.valueHistory.getLast()).isEqualTo(sum.get());

        kanalarz.newContext().resumes(contextId).rollbackNow();

        assertThat(service.value).isEqualTo(0);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(stepsRan.get() * 2 + nestedStepsRan.get());
    }

    // has to be outside the test method because of a java compiler bug
    // https://bugs.openjdk.org/browse/JDK-8349480
    sealed interface Executed {
        record AddedOne(int value) implements Executed {}
        record AddedMany(List<Integer> value) implements Executed {}
    }

    @Test
    void concurrentStepsResumeReplayTest() {
        UUID contextId = UUID.randomUUID();
        var sum = new AtomicInteger(0);
        var stepsRan = new AtomicInteger(0);
        var nestedStepsRan = new AtomicInteger(0);

        List<Executed> executed = Collections.synchronizedList(new ArrayList<>());

        Consumer<KanalarzContext> job = ctx -> {
            for (int j = 0; j < 50; j++) {
                Kanalarz.forkRunVirtual(() -> {
                    if (Math.random() < .2) {
                        int value = (int) Math.floor(Math.random() * 100);
                        executed.add(new Executed.AddedOne(value));
                        sum.addAndGet(value);
                        steps.add(value);
                        stepsRan.addAndGet(1);
                    } else {
                        var values = IntStream.range(0, 50)
                            .map(it -> (int) Math.floor(Math.random() * 10))
                            .peek(sum::addAndGet)
                            .boxed()
                            .toList();
                        executed.add(new Executed.AddedMany(values.stream().toList()));
                        steps.addAllConcurrently(values);
                        stepsRan.addAndGet(values.size());
                        nestedStepsRan.addAndGet(1);
                    }
                });
            }
        };

        kanalarz.newContext().resumes(contextId).consume(job);

        var sumAfterFirstRun = sum.get();
        var calcedSum = executed.stream().flatMap(it -> switch (it) {
            case Executed.AddedOne(int v) -> Stream.of(v);
            case Executed.AddedMany(List<Integer> v) -> v.stream();
        }).mapToInt(it -> it).sum();

        assertThat(sumAfterFirstRun).isEqualTo(calcedSum);
        assertThat(service.value).isEqualTo(sumAfterFirstRun);
        assertThat(service.valueHistory.getLast()).isEqualTo(sumAfterFirstRun);

        Consumer<KanalarzContext> resumeReplayJob = ctx -> {
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (var ex : executed) {
                futures.add(Kanalarz.forkRunVirtual(() -> {
                    switch (ex) {
                        case Executed.AddedOne(int value) ->
                            steps.add(value);
                        case Executed.AddedMany(List<Integer> value) ->
                            steps.addAllConcurrently(value);
                    }
                }));
            }
            futures.forEach(CompletableFuture::join);
            job.accept(ctx);
        };

        kanalarz.newContext().resumes(contextId)
            .option(Kanalarz.Option.OUT_OF_ORDER_REPLAY)
            .option(Kanalarz.Option.NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED)
            .consumeResumeReplay(resumeReplayJob);

        assertThat(sum.get()).isGreaterThan(sumAfterFirstRun);
        assertThat(service.value).isEqualTo(sum.get());
        assertThat(service.valueHistory.getLast()).isEqualTo(sum.get());

        kanalarz.newContext().resumes(contextId).rollbackNow();

        assertThat(service.value).isEqualTo(0);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
            .hasSize(stepsRan.get() * 2 + nestedStepsRan.get());
    }
}
