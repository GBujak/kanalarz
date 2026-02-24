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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class EdgeCaseService {

    private final Map<UUID, String> created = new ConcurrentHashMap<>();
    private final Set<UUID> failRollbackOnce = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> rollbackAttempts = new ConcurrentHashMap<>();

    UUID create(String value, boolean rollbackShouldFailOnce) {
        var id = UUID.randomUUID();
        created.put(id, value);
        if (rollbackShouldFailOnce) {
            failRollbackOnce.add(id);
        }
        return id;
    }

    void rollbackCreate(UUID id) {
        rollbackAttempts.merge(id, 1, Integer::sum);
        if (failRollbackOnce.remove(id)) {
            throw new RuntimeException("planned rollback failure");
        }
        created.remove(id);
    }

    Map<UUID, String> created() {
        return Map.copyOf(created);
    }

    int rollbackAttempts(UUID id) {
        return rollbackAttempts.getOrDefault(id, 0);
    }

    void clear() {
        created.clear();
        failRollbackOnce.clear();
        rollbackAttempts.clear();
    }
}

@Component
@StepsHolder("edge-case-steps")
class EdgeCaseSteps {

    @Autowired private EdgeCaseService service;

    @Step("create")
    UUID create(String value, boolean rollbackShouldFailOnce) {
        return service.create(value, rollbackShouldFailOnce);
    }

    @Rollback("create")
    void rollbackCreate(@RollforwardOut UUID id) {
        service.rollbackCreate(id);
    }
}

@SpringBootTest
public class EdgeCaseTests {

    @Autowired private Kanalarz kanalarz;
    @Autowired private EdgeCaseService service;
    @Autowired private EdgeCaseSteps steps;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void shouldRejectIncompatibleRollbackOptions() {
        assertThatThrownBy(() ->
            kanalarz.newContext()
                .option(Kanalarz.Option.SKIP_FAILED_ROLLBACKS)
                .option(Kanalarz.Option.RETRY_FAILED_ROLLBACKS)
                .consume(ctx -> {})
        ).isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldSkipPreviouslyFailedRollbackWhenSkipOptionEnabled() {
        var contextId = UUID.randomUUID();
        var crash = new RuntimeException("crash");

        var idThatShouldRollback = new AtomicReference<UUID>();
        var idWithFailedRollback = new AtomicReference<UUID>();

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                idThatShouldRollback.set(steps.create("first", false));
                idWithFailedRollback.set(steps.create("second", true));
                throw crash;
            })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzRollbackStepFailedException.class)
            .matches(ex ->
                ex instanceof KanalarzException.KanalarzRollbackStepFailedException e
                    && e.getInitialStepFailedException() == crash
            );

        var rollbacked = idThatShouldRollback.get();
        var failed = idWithFailedRollback.get();

        assertThat(service.rollbackAttempts(rollbacked)).isZero();
        assertThat(service.rollbackAttempts(failed)).isEqualTo(1);

        kanalarz.newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.SKIP_FAILED_ROLLBACKS)
            .rollbackNow();

        assertThat(service.rollbackAttempts(rollbacked)).isEqualTo(1);
        assertThat(service.rollbackAttempts(failed)).isEqualTo(1);
        assertThat(service.created()).containsOnlyKeys(failed);
    }

    @Test
    void shouldRetryPreviouslyFailedRollbackWhenRetryOptionEnabled() {
        var contextId = UUID.randomUUID();
        var crash = new RuntimeException("crash");

        var idThatShouldRollback = new AtomicReference<UUID>();
        var idWithFailedRollback = new AtomicReference<UUID>();

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                idThatShouldRollback.set(steps.create("first", false));
                idWithFailedRollback.set(steps.create("second", true));
                throw crash;
            })
        ).isExactlyInstanceOf(KanalarzException.KanalarzRollbackStepFailedException.class);

        var rollbacked = idThatShouldRollback.get();
        var failed = idWithFailedRollback.get();

        assertThat(service.rollbackAttempts(rollbacked)).isZero();
        assertThat(service.rollbackAttempts(failed)).isEqualTo(1);

        kanalarz.newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.RETRY_FAILED_ROLLBACKS)
            .rollbackNow();

        assertThat(service.rollbackAttempts(rollbacked)).isEqualTo(1);
        assertThat(service.rollbackAttempts(failed)).isEqualTo(2);
        assertThat(service.created()).isEmpty();
    }

    @Test
    void shouldContinueRollbackWhenAllRollbackStepsFallible() {
        var contextId = UUID.randomUUID();
        var crash = new RuntimeException("crash");

        var idThatShouldRollback = new AtomicReference<UUID>();
        var idWithFailedRollback = new AtomicReference<UUID>();

        assertThatThrownBy(() ->
            kanalarz.newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.ALL_ROLLBACK_STEPS_FALLIBLE)
                .consume(ctx -> {
                    idThatShouldRollback.set(steps.create("first", false));
                    idWithFailedRollback.set(steps.create("second", true));
                    throw crash;
                })
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCause(crash);

        var rollbacked = idThatShouldRollback.get();
        var failed = idWithFailedRollback.get();

        assertThat(service.rollbackAttempts(rollbacked)).isEqualTo(1);
        assertThat(service.rollbackAttempts(failed)).isEqualTo(1);
        assertThat(service.created()).containsOnlyKeys(failed);
    }

    @Test
    void shouldFailStartResumeReplayWithoutResumes() {
        assertThatThrownBy(() ->
            kanalarz.newContext().startResumeReplay(ctx -> null)
        ).isExactlyInstanceOf(KanalarzException.KanalarzIllegalUsageException.class);
    }

    @Test
    void shouldFailRollbackNowWithoutResumes() {
        assertThatThrownBy(() ->
            kanalarz.newContext().rollbackNow()
        ).isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailForkJoinOutsideContext() {
        assertThatThrownBy(() -> Kanalarz.forkJoin(List.of(1), it -> it + 1))
            .isExactlyInstanceOf(KanalarzException.KanalarzNoContextException.class);
    }

    @Test
    void shouldFailForkConsumeOutsideContext() {
        assertThatThrownBy(() -> Kanalarz.forkConsume(List.of(1), it -> {}))
            .isExactlyInstanceOf(KanalarzException.KanalarzNoContextException.class);
    }

    @Test
    void shouldRejectInvalidMaxParallelismInForkJoin() {
        assertThatThrownBy(() -> Kanalarz.forkJoin(List.of(1), 0, it -> it + 1))
            .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Kanalarz.forkJoin(List.of(1), -1, it -> it + 1))
            .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMaxParallelismInForkConsume() {
        assertThatThrownBy(() -> Kanalarz.forkConsume(List.of(1), 0, it -> {}))
            .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Kanalarz.forkConsume(List.of(1), -1, it -> {}))
            .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFailForceCancelWhenContextNotRunning() {
        assertThatThrownBy(() -> Kanalarz.cancelContextForceDeferRollback(UUID.randomUUID()))
            .isExactlyInstanceOf(IllegalStateException.class);
    }
}
