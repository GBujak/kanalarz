package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class ResumeReplayTestService {

    private final List<String> messages = new ArrayList<>();

    public List<String> add(String message) {
        messages.add(message);
        return List.copyOf(messages);
    }

    public void remove(String message) {
        messages.remove(messages.lastIndexOf(message));
    }

    public List<String> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public void clear() {
        messages.clear();
    }
}

@Component
@StepsHolder("resume-replay-test")
class ResumeReplayTestSteps {

    @Autowired
    private ResumeReplayTestService service;

    @Step("add")
    public List<String> add(String message) {
        return service.add(message);
    }

    @Rollback("add")
    public void remove(String message) {
        service.remove(message);
    }
}

@SpringBootTest
public class ResumeReplayTests {

    @Autowired
    private Kanalarz kanalarz;

    @Autowired
    private KanalarzPersistence persistence;

    @Autowired
    private ResumeReplayTestService service;

    @Autowired
    private ResumeReplayTestSteps steps;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void shouldResumeReplay() {
        UUID contextId = UUID.randomUUID();
        var exception = new RuntimeException();

        assertThatThrownBy(() ->
            kanalarz
                .newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.DEFER_ROLLBACK)
                .consume(ctx -> {
                    assertThat(steps.add("test-1")).isEqualTo(
                        List.of("test-1")
                    );
                    assertThat(steps.add("test-2")).isEqualTo(
                        List.of("test-1", "test-2")
                    );
                    throw exception;
                })
        )
            .isExactlyInstanceOf(
                KanalarzException.KanalarzThrownOutsideOfStepException.class
            )
            .hasCause(exception);

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2")
        );

        kanalarz
            .newContext()
            .resumes(contextId)
            .consumeResumeReplay(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3", "test-3")
                );
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2", "test-3", "test-3")
        );
    }

    @Test
    void shouldFailOnNewStepBeforeAllReplayed() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.DEFER_ROLLBACK)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
            });

        assertThatThrownBy(() ->
            kanalarz
                .newContext()
                .resumes(contextId)
                .consumeResumeReplay(ctx -> {
                    assertThat(steps.add("test-1")).isEqualTo(
                        List.of("test-1")
                    );
                    assertThat(steps.add("test-2")).isEqualTo(
                        List.of("test-1", "test-2")
                    );
                    steps.add("test-4");
                })
        )
            .isExactlyInstanceOf(
                KanalarzException.KanalarzThrownOutsideOfStepException.class
            )
            .hasCauseExactlyInstanceOf(
                KanalarzException
                    .KanalarzNewStepBeforeReplayEndedException.class
            );

        assertThat(service.getMessages()).isEmpty();
    }

    @Test
    void shouldFailOnNewStepBeforeAllReplayedNoRollback() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
            });

        assertThatThrownBy(() ->
            kanalarz
                .newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.DEFER_ROLLBACK)
                .consumeResumeReplay(ctx -> {
                    assertThat(steps.add("test-1")).isEqualTo(
                        List.of("test-1")
                    );
                    assertThat(steps.add("test-2")).isEqualTo(
                        List.of("test-1", "test-2")
                    );
                    steps.add("test-4");
                })
        )
            .isExactlyInstanceOf(
                KanalarzException.KanalarzThrownOutsideOfStepException.class
            )
            .hasCauseExactlyInstanceOf(
                KanalarzException
                    .KanalarzNewStepBeforeReplayEndedException.class
            );

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2", "test-3")
        );
    }

    @Test
    void shouldAllowOutOfOrder() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
            });

        kanalarz
            .newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.OUT_OF_ORDER_REPLAY)
            .consumeResumeReplay(ctx -> {
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
                assertThat(steps.add("test-4")).isEqualTo(
                    List.of("test-1", "test-2", "test-3", "test-4")
                );
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2", "test-3", "test-4")
        );
    }

    @Test
    void shouldFailWhenAllowOutOfOrderAndEncountersNewStepBeforeAllReplayed() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
            });

        assertThatThrownBy(() ->
            kanalarz
                .newContext()
                .resumes(contextId)
                .option(Kanalarz.Option.OUT_OF_ORDER_REPLAY)
                .consumeResumeReplay(ctx -> {
                    assertThat(steps.add("test-2")).isEqualTo(
                        List.of("test-1", "test-2")
                    );
                    assertThat(steps.add("test-1")).isEqualTo(
                        List.of("test-1")
                    );
                    steps.add("test-4");
                })
        )
            .isExactlyInstanceOf(
                KanalarzException.KanalarzThrownOutsideOfStepException.class
            )
            .hasCauseExactlyInstanceOf(
                KanalarzException
                    .KanalarzNewStepBeforeReplayEndedException.class
            );

        assertThat(service.getMessages()).isEmpty();
    }

    @Test
    void shouldAllowNewStepsAndOutOfOrder() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
            });

        kanalarz
            .newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.OUT_OF_ORDER_REPLAY)
            .option(Kanalarz.Option.NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED)
            .consumeResumeReplay(ctx -> {
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-4")).isEqualTo(
                    List.of("test-1", "test-2", "test-3", "test-4")
                );
                assertThat(steps.add("test-3")).isEqualTo(
                    List.of("test-1", "test-2", "test-3")
                );
                assertThat(steps.add("test-5")).isEqualTo(
                    List.of("test-1", "test-2", "test-3", "test-4", "test-5")
                );
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2", "test-3", "test-4", "test-5")
        );
    }

    @Test
    void shouldNotAllowNewStepsWithoutOutOfOrder() {
        assertThatThrownBy(() ->
            kanalarz
                .newContext()
                .option(
                    Kanalarz.Option.NEW_STEPS_CAN_EXECUTE_BEFORE_ALL_REPLAYED
                )
                .consume(ctx -> {})
        ).isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRollbackOnNotReplayed() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2")
        );

        assertThatThrownBy(() ->
            kanalarz
                .newContext()
                .resumes(contextId)
                .consumeResumeReplay(ctx -> {
                    assertThat(steps.add("test-1")).isEqualTo(
                        List.of("test-1")
                    );
                })
        )
            .isExactlyInstanceOf(
                KanalarzException.KanalarzThrownOutsideOfStepException.class
            )
            .hasCauseExactlyInstanceOf(
                KanalarzException.KanalarzNotAllStepsReplayedException.class
            );

        assertThat(service.getMessages()).isEmpty();
    }

    @Test
    void shouldRollbackOnlyNotReplayed() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2")
        );

        kanalarz
            .newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.ROLLBACK_ONLY_NOT_REPLAYED_STEPS)
            .consumeResumeReplay(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
            });

        assertThat(service.getMessages()).isEqualTo(List.of("test-1"));
    }

    @Test
    void shouldIgnoreNotReplayed() {
        UUID contextId = UUID.randomUUID();

        kanalarz
            .newContext()
            .resumes(contextId)
            .consume(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
                assertThat(steps.add("test-2")).isEqualTo(
                    List.of("test-1", "test-2")
                );
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2")
        );

        kanalarz
            .newContext()
            .resumes(contextId)
            .option(Kanalarz.Option.IGNORE_NOT_REPLAYED_STEPS)
            .consumeResumeReplay(ctx -> {
                assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
            });

        assertThat(service.getMessages()).isEqualTo(
            List.of("test-1", "test-2")
        );
    }
}
