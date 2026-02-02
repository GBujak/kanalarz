package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
        Kanalarz.forkConsume(values, self::add);
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
            Kanalarz.forkConsume(IntStream.range(0, 100).boxed().toList(), (i) -> {
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
        });

        assertThat(service.value).isEqualTo(sum.get());
        assertThat(service.valueHistory.getLast()).isEqualTo(sum.get());

        kanalarz.newContext().resumes(contextId).rollbackNow();

        assertThat(service.value).isEqualTo(0);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
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

        Map<Integer, Executed> executed = Collections.synchronizedMap(new HashMap<>(50));
        var indexes = IntStream.range(0, 50).boxed().toList();

        Consumer<KanalarzContext> job = ctx -> {
            Kanalarz.forkConsume(indexes, (i) -> {
                if (Math.random() < .2) {
                    int value = (int) Math.floor(Math.random() * 100);
                    executed.put(i, new Executed.AddedOne(value));
                    sum.addAndGet(value);
                    steps.add(value);
                    stepsRan.addAndGet(1);
                } else {
                    var values = IntStream.range(0, 50)
                        .map(it -> (int) Math.floor(Math.random() * 10))
                        .peek(sum::addAndGet)
                        .boxed()
                        .toList();
                    executed.put(i, new Executed.AddedMany(values.stream().toList()));
                    steps.addAllConcurrently(values);
                    stepsRan.addAndGet(values.size());
                    nestedStepsRan.addAndGet(1);
                }
            });
        };

        kanalarz.newContext().resumes(contextId).consume(job);

        var sumAfterFirstRun = sum.get();
        var calcedSum = executed.values().stream().flatMap(it -> switch (it) {
            case Executed.AddedOne(int v) -> Stream.of(v);
            case Executed.AddedMany(List<Integer> v) -> v.stream();
        }).mapToInt(it -> it).sum();

        assertThat(sumAfterFirstRun).isEqualTo(calcedSum);
        assertThat(service.value).isEqualTo(sumAfterFirstRun);
        assertThat(service.valueHistory.getLast()).isEqualTo(sumAfterFirstRun);

        Consumer<KanalarzContext> resumeReplayJob = ctx -> {
            Kanalarz.forkConsume(indexes, (i) -> {
                switch (executed.get(i)) {
                    case Executed.AddedOne(int value) ->
                        steps.add(value);
                    case Executed.AddedMany(List<Integer> value) ->
                        steps.addAllConcurrently(value);
                }
            });
            job.accept(ctx);
        };

        kanalarz.newContext().resumes(contextId)
            .consumeResumeReplay(resumeReplayJob);

        assertThat(sum.get()).isGreaterThan(sumAfterFirstRun);
        assertThat(service.value).isEqualTo(sum.get());
        assertThat(service.valueHistory.getLast()).isEqualTo(sum.get());

        kanalarz.newContext().resumes(contextId).rollbackNow();

        assertThat(service.value).isEqualTo(0);
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(stepsRan.get() * 2 + nestedStepsRan.get());
    }

    @Test
    void thunderingHerdTest() {
        var contextId = UUID.randomUUID();
        var values = IntStream.range(0, 10_000).boxed().toList();
        kanalarz.newContext().resumes(contextId).consume(ctx -> {
            Kanalarz.forkConsume(values, value -> {
                try { Thread.sleep(100); } catch (Throwable ignored) { }
                steps.add(value);
            });
        });

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(values.size());
        assertThat(service.value).isEqualTo(values.stream().mapToInt(Integer::intValue).sum());

        kanalarz.newContext().resumes(contextId).rollbackNow();

        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId))
            .hasSize(values.size() * 2);
        assertThat(service.value).isZero();
    }
}
