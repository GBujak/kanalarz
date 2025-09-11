package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class CancellingTestsService {

    Lock lock = new ReentrantLock();
    String value = null;

    String setValue(String value) {
        try {
            var tmp = this.value;
            this.value = value;

            // lock after setting value - the lock is to trap the step here, not to synchronize
            lock.lock();
            return tmp;
        } finally {
            lock.unlock();
        }
    }
}

@Component
@StepsHolder(identifier = "cancellling-tests-steps")
class CancellingTestsSteps {

    @Autowired private CancellingTestsService service;

    @Step(identifier = "set-value")
    String setValue(String value) {
        return service.setValue(value);
    }

    @Rollback(forStep = "set-value")
    void rollbackSetValue(@RollforwardOut String oldValue) {
        service.setValue(oldValue);
    }
}

@SpringBootTest
public class CancellingTests {

    @Autowired private CancellingTestsService service;
    @Autowired private CancellingTestsSteps steps;
    @Autowired private Kanalarz kanalarz;
    @Autowired private KanalarzPersistence persistence;

    @BeforeEach
    void beforeEach() {
        service.value = null;
    }

    @Test
    void shouldFailToCancelNotRunning() {
        assertThatThrownBy(() -> kanalarz.cancelContext(UUID.randomUUID()))
            .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailToCancelTwice() throws InterruptedException {
        var contextId = UUID.randomUUID();
        service.lock.lock();

        Thread.ofVirtual().start(() -> {
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                steps.setValue("test");
            });
        });

        Thread.sleep(100);

        kanalarz.cancelContext(contextId);
        assertThatThrownBy(() -> kanalarz.cancelContext(contextId))
            .isExactlyInstanceOf(IllegalStateException.class);

        service.lock.unlock();
    }

    @Test
    void shouldCancelCorrectly() throws InterruptedException {
        var contextId = UUID.randomUUID();
        service.lock.lock();

        try (var x = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = x.submit(() -> {
                kanalarz.newContext().resumes(contextId).consume(ctx -> {
                    steps.setValue("test");
                    steps.setValue("test-2");
                });
            });

            Thread.sleep(100);

            assertThat(service.value).isEqualTo("test");

            kanalarz.cancelContext(contextId);
            service.lock.unlock();

            assertThatThrownBy(result::get)
                .isExactlyInstanceOf(ExecutionException.class)
                .hasCauseExactlyInstanceOf(KanalarzException.KanalarzContextCancelledException.class);

            assertThat(service.value).isNull();
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(2);
        }
    }

    @Test
    void shouldCancelCorrectlyWithDeferredRollback() throws InterruptedException {
        var contextId = UUID.randomUUID();
        service.lock.lock();

        try (var x = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = x.submit(() -> {
                kanalarz.newContext().resumes(contextId).option(Kanalarz.Option.DEFER_ROLLBACK)
                    .consume(ctx -> {
                        steps.setValue("test");
                        steps.setValue("test-2");
                    });
            });

            Thread.sleep(100);

            assertThat(service.value).isEqualTo("test");

            kanalarz.cancelContext(contextId);
            service.lock.unlock();

            assertThatThrownBy(result::get)
                .isExactlyInstanceOf(ExecutionException.class)
                .hasCauseExactlyInstanceOf(KanalarzException.KanalarzContextCancelledException.class);

            assertThat(service.value).isEqualTo("test");
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(1);

            kanalarz.newContext().resumes(contextId).rollbackNow();

            assertThat(service.value).isNull();
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(2);
        }
    }

    @Test
    void shouldCancelCorrectlyWithForceDeferredRollback() throws InterruptedException {
        var contextId = UUID.randomUUID();
        service.lock.lock();

        try (var x = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = x.submit(() -> {
                kanalarz.newContext().resumes(contextId).consume(ctx -> {
                    steps.setValue("test");
                    steps.setValue("test-2");
                });
            });

            Thread.sleep(100);

            assertThat(service.value).isEqualTo("test");

            kanalarz.cancelContextForceDeferRollback(contextId);
            service.lock.unlock();

            assertThatThrownBy(result::get)
                .isExactlyInstanceOf(ExecutionException.class)
                .hasCauseExactlyInstanceOf(KanalarzException.KanalarzContextCancelledException.class);

            assertThat(service.value).isEqualTo("test");
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(1);

            kanalarz.newContext().resumes(contextId).rollbackNow();

            assertThat(service.value).isNull();
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(2);
        }
    }

    @Test
    void shouldCancelCorrectlyWithDeferRollbackAndForceDeferredRollback() throws InterruptedException {
        var contextId = UUID.randomUUID();
        service.lock.lock();

        try (var x = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = x.submit(() -> {
                kanalarz.newContext().resumes(contextId).option(Kanalarz.Option.DEFER_ROLLBACK)
                    .consume(ctx -> {
                        steps.setValue("test");
                        steps.setValue("test-2");
                    });
            });

            Thread.sleep(100);

            assertThat(service.value).isEqualTo("test");

            kanalarz.cancelContextForceDeferRollback(contextId);
            service.lock.unlock();

            assertThatThrownBy(result::get)
                .isExactlyInstanceOf(ExecutionException.class)
                .hasCauseExactlyInstanceOf(KanalarzException.KanalarzContextCancelledException.class);

            assertThat(service.value).isEqualTo("test");
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(1);

            kanalarz.newContext().resumes(contextId).rollbackNow();

            assertThat(service.value).isNull();
            assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId))
                .hasSize(2);
        }
    }
}
