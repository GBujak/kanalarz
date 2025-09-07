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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

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
            lock.lock();
        }
    }

    public void clear() {
        value = 0;
        valueHistory.clear();
    }
}

@Component
@StepsHolder(identifier = "concurrent-steps")
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

    @Step(identifier = "add")
    void add(int value) {
        service.add(value);
    }

    @Rollback(forStep = "add")
    void addRollback(int value) {
        service.undoAdd(value);
    }

    @Step(identifier = "add-all-concurrently")
    void addAllConcurrently(List<Integer> values) {
        try (var x = Executors.newFixedThreadPool(8)) {
            for (var value : values) {
                x.submit(() -> self.add(value));
            }
        }
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
            try (ExecutorService x = Executors.newFixedThreadPool(8)) {
                IntStream.range(0, 100).forEach((it) -> {
                    x.submit(() -> {
                        if (Math.random() < .2) {
                            int value = (int) Math.floor(Math.random() * 100);
                            sum.addAndGet(value);
                            steps.add(value);
                            stepsRan.addAndGet(1);
                        } else {
                            var values = IntStream.range(0, 100)
                                .boxed()
                                .map(integer -> (int) Math.floor(Math.random() * 10))
                                .peek(sum::addAndGet)
                                .toList();
                            steps.addAllConcurrently(values);
                            stepsRan.addAndGet(values.size());
                            nestedStepsRan.addAndGet(1);
                        }
                    });
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
}
