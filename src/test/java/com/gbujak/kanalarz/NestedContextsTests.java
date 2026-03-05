package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@NullMarked
@Service
class NestedContextsTestsService {

    public static final RuntimeException ERR = new RuntimeException();

    final Map<UUID, String> postsA = Collections.synchronizedMap(new HashMap<>());
    final Map<UUID, String> postsB = Collections.synchronizedMap(new HashMap<>());

    @Nullable
    String getPostA(UUID id) {
        return postsA.get(id);
    }

    UUID submitPostA(String post) {
        var id = UUID.randomUUID();
        postsA.put(id, post);
        return id;
    }

    void removePostA(UUID id) {
        if (postsA.remove(id) == null) {
            throw ERR;
        }
    }

    @Nullable
    String getPostB(UUID id) {
        return postsB.get(id);
    }

    UUID submitPostB(String post) {
        var id = UUID.randomUUID();
        postsB.put(id, post);
        return id;
    }

    void removePostB(UUID id) {
        if (postsB.remove(id) == null) {
            throw ERR;
        }
    }

    void clear() {
        postsA.clear();
        postsB.clear();
    }
}

@NullMarked
@Component
@StepsHolder("nested-contexts-test-steps")
class NestedContextsTestsSteps {

    @Autowired private NestedContextsTestsService service;

    @Step("submit-post-a")
    public UUID submitPostA(String post) {
        return service.submitPostA(post);
    }

    @Rollback("submit-post-a")
    public void rollbackSubmitPostA(@RollforwardOut UUID id) {
        service.removePostA(id);
    }

    @Step("submit-post-b")
    public UUID submitPostB(String post) {
        return service.submitPostB(post);
    }

    @Rollback("submit-post-b")
    public void rollbackSubmitPostB(@RollforwardOut UUID id) {
        service.removePostB(id);
    }
}

@NullMarked
@SpringBootTest
public class NestedContextsTests {

    @Autowired private NestedContextsTestsService service;
    @Autowired private NestedContextsTestsSteps steps;
    @Autowired private Kanalarz kanalarz;
    @Autowired private KanalarzPersistence persistence;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void childContextFailingShouldNotFailParentIfCaught() {
        var exception = new RuntimeException();
        List<UUID> postIdsA = new ArrayList<>();
        List<UUID> postIdsB = new ArrayList<>();

        kanalarz.newContext().consume((ignored) -> {
            postIdsA.add(steps.submitPostA("test 1"));
            assertThatThrownBy(() ->
                kanalarz.newContext().consume((ignored2) -> {
                    postIdsB.add(steps.submitPostB("test 1"));
                    postIdsB.add(steps.submitPostB("test 2"));
                    throw exception;
                })
            ).hasCause(exception);
            postIdsA.add(steps.submitPostA("test 2"));
        });

        assertThat(service.getPostA(postIdsA.get(0))).isEqualTo("test 1");
        assertThat(service.getPostA(postIdsA.get(1))).isEqualTo("test 2");
        assertThat(service.getPostB(postIdsB.get(0))).isNull();
        assertThat(service.getPostB(postIdsB.get(1))).isNull();
    }

    @Test
    void childContextFailingShouldFailParentIfNotCaught() {
        var exception = new RuntimeException();
        List<UUID> postIdsA = new ArrayList<>();
        List<UUID> postIdsB = new ArrayList<>();

        assertThatThrownBy(() ->
            kanalarz.newContext().consume((ignored) -> {
                postIdsA.add(steps.submitPostA("test 1"));
                kanalarz.newContext().consume((ignored2) -> {
                    postIdsB.add(steps.submitPostB("test 1"));
                    postIdsB.add(steps.submitPostB("test 2"));
                    throw exception;
                });
                postIdsA.add(steps.submitPostA("test 2"));
            })
        ).isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCauseExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasRootCause(exception);

        assertThat(postIdsA).hasSize(1);
        assertThat(service.getPostA(postIdsA.get(0))).isNull();
        assertThat(service.getPostB(postIdsB.get(0))).isNull();
        assertThat(service.getPostB(postIdsB.get(1))).isNull();
    }

    @Test
    void parentFailingShouldRollbackTheChildAsWell() {
        var exception = new RuntimeException();
        List<UUID> postIdsA = new ArrayList<>();
        List<UUID> postIdsB = new ArrayList<>();

        assertThatThrownBy(() -> kanalarz.newContext().consume((ignored) -> {
            postIdsA.add(steps.submitPostA("test 1"));
            kanalarz.newContext().consume((ignored2) -> {
                postIdsB.add(steps.submitPostB("test 1"));
                postIdsB.add(steps.submitPostB("test 2"));
            });
            postIdsA.add(steps.submitPostA("test 2"));
            throw exception;
        })).hasCause(exception);

        assertThat(service.getPostA(postIdsA.get(0))).isNull();
        assertThat(service.getPostA(postIdsA.get(1))).isNull();
        assertThat(service.getPostB(postIdsB.get(0))).isNull();
        assertThat(service.getPostB(postIdsB.get(1))).isNull();
    }

    @Test
    void nestedContextWithForkJoinShouldRollbackIndividually() {
        List<String> inputs = List.of("p1", "p2", "p3");
        List<UUID> postIdsA = new ArrayList<>();
        AtomicReference<List<UUID>> postIdsB = new AtomicReference<>(List.of());

        assertThatThrownBy(() -> kanalarz.newContext().consume(ctx -> {
            postIdsA.add(steps.submitPostA("parent-1"));

            kanalarz.newContext().consume(subCtx -> {
                var results = Kanalarz.forkJoin(inputs, (item) -> steps.submitPostB(item));
                postIdsB.set(results);
                throw new RuntimeException("fail-inside-subcontext");
            });
        })).hasMessageContaining("fail-inside-subcontext");

        assertThat(service.getPostA(postIdsA.get(0))).isNull();
        Objects.requireNonNull(postIdsB.get()).forEach(id -> assertThat(service.getPostB(id)).isNull());
    }

    @Test
    void siblingSubContextsShouldNotInterfereWithEachOther() {
        kanalarz.newContext().consume(ctx -> {
            kanalarz.newContext().consume(ctx1 -> {
                steps.submitPostA("sub-1-step-1");
            });

            kanalarz.newContext().consume(ctx2 -> {
                steps.submitPostB("sub-2-step-1");
            });
        });

        assertThat(service.postsA.values()).hasSize(1);
        assertThat(service.postsB.values()).hasSize(1);
    }


    @Test
    void subContextRollbackShouldBeTransparentToParentReplay() {
        UUID contextId = UUID.randomUUID();
        AtomicBoolean shouldFailSub = new AtomicBoolean(true);

        Consumer<KanalarzContext> job = ctx -> {
            steps.submitPostA("parent-start");

            if (shouldFailSub.get()) {
                assertThatThrownBy(() ->
                    kanalarz.newContext().consume(sub -> {
                        steps.submitPostB("sub-task");
                        throw new RuntimeException("fail-sub");
                    })
                ).hasMessageContaining("fail-sub");
                shouldFailSub.set(false);
                throw new RuntimeException("crash-parent");
            }

            steps.submitPostA("parent-end");
        };

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(contextId).option(Kanalarz.Option.DEFER_ROLLBACK).consume(job)
        ).hasMessageContaining("crash-parent");

        kanalarz.newContext().resumes(contextId).consumeResumeReplay(job);

        assertThat(service.postsA.values()).containsExactlyInAnyOrder("parent-start", "parent-end");
        assertThat(service.postsB.values()).isEmpty();
    }

    @Test
    void deepNestedReplayShouldExecuteExactlyOnce() {
        UUID jobId = UUID.randomUUID();
        AtomicInteger deepCallCount = new AtomicInteger(0);

        Consumer<KanalarzContext> job = ctx -> {
            steps.submitPostA("root-step"); // r.s0
            kanalarz.newContext().consume(ctx2 -> {
                steps.submitPostB("mid-step"); // r.c1.s0
                kanalarz.newContext().consume(ctx3 -> {
                    steps.submitPostA("deep-step"); // r.c1.c1.s0
                    if (deepCallCount.incrementAndGet() == 1) {
                        throw new RuntimeException("deep-crash");
                    }
                    steps.submitPostB("final-step"); // r.c1.c1.s1
                });
            });
        };

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(jobId).option(Kanalarz.Option.DEFER_ROLLBACK).consume(job)
        ).hasMessageContaining("deep-crash");

        kanalarz.newContext().resumes(jobId).consumeResumeReplay(job);

        assertThat(service.postsA.values()).containsExactlyInAnyOrder("root-step", "deep-step");
        assertThat(service.postsB.values()).containsExactlyInAnyOrder("mid-step", "final-step");
    }

    @Test
    void siblingContextsShouldHaveIndependentTimelines() {
        UUID jobId = UUID.randomUUID();
        AtomicBoolean alreadyCrashed = new AtomicBoolean(false);

        Consumer<KanalarzContext> job = ctx -> {
            kanalarz.newContext().consume(sub1 -> {
                steps.submitPostA("child-1");
            });

            kanalarz.newContext().consume(sub2 -> {
                steps.submitPostB("child-2");
            });

            if (!alreadyCrashed.get()) {
                alreadyCrashed.set(true);
                throw new RuntimeException("crash");
            }
        };

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(jobId).option(Kanalarz.Option.DEFER_ROLLBACK).consume(job)
        ).hasMessageContaining("crash");

        kanalarz.newContext().resumes(jobId).consumeResumeReplay(job);

        assertThat(service.postsA.values()).containsExactly("child-1");
        assertThat(service.postsB.values()).containsExactly("child-2");
    }

    @Test
    void parentShouldBeAbleToCatchAndResumeReplayFailedSubContext() {
        UUID subJobId = UUID.randomUUID();
        AtomicBoolean shouldFail = new AtomicBoolean(true);

        Consumer<KanalarzContext> nestedJob = sub -> {
            steps.submitPostB("sub-post-1");
            if (shouldFail.get()) {
                throw new RuntimeException("sub-failure");
            }
            steps.submitPostB("sub-post-2");
        };

        kanalarz.newContext().consume(ctx -> {
            steps.submitPostA("parent-start");

            try {
                kanalarz.newContext()
                    .resumes(subJobId)
                    .option(Kanalarz.Option.DEFER_ROLLBACK)
                    .consume(nestedJob);
            } catch (Exception e) {
                shouldFail.set(false);
            }

            kanalarz.newContext()
                .resumes(subJobId)
                .consumeResumeReplay(nestedJob);

            steps.submitPostA("parent-end");
        });

        assertThat(service.postsA.values()).containsExactlyInAnyOrder("parent-start", "parent-end");
        assertThat(service.postsB.values()).containsExactlyInAnyOrder("sub-post-1", "sub-post-2");
    }

    @Test
    void namedParentRerunShouldAllowResumingReplayOnlyFailedChildInsideParent() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        AtomicBoolean shouldFailChild = new AtomicBoolean(true);

        Consumer<KanalarzContext> childJob = child -> {
            steps.submitPostA("child-1");
            steps.submitPostB("child-2");
            if (shouldFailChild.getAndSet(false)) {
                throw new RuntimeException("child-failure");
            }
            steps.submitPostA("child-after-replay");
        };

        kanalarz.newContext().resumes(parentId).consume(parent -> {
            steps.submitPostA("parent-1-start");
            assertThatThrownBy(() ->
                kanalarz.newContext()
                    .resumes(childId)
                    .option(Kanalarz.Option.DEFER_ROLLBACK)
                    .consume(childJob)
            ).hasMessageContaining("child-failure");
            steps.submitPostB("parent-1-end");
        });

        assertThat(service.postsA.values()).containsExactlyInAnyOrder("parent-1-start", "child-1");
        assertThat(service.postsB.values()).containsExactlyInAnyOrder("child-2", "parent-1-end");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId)).hasSize(2);

        kanalarz.newContext().resumes(parentId).consume(parent -> {
            steps.submitPostA("parent-2-start");
            kanalarz.newContext().resumes(childId).consumeResumeReplay(childJob);
            steps.submitPostB("parent-2-end");
        });

        assertThat(service.postsA.values())
            .containsExactlyInAnyOrder("parent-1-start", "child-1", "parent-2-start", "child-after-replay");
        assertThat(service.postsB.values()).containsExactlyInAnyOrder("child-2", "parent-1-end", "parent-2-end");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId)).hasSize(3);
        assertThat(
            persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId).stream()
                .map(KanalarzPersistence.StepExecutedInfo::executionPath)
                .toList()
        ).containsExactly(
            "r.c-" + childId + ".s0",
            "r.c-" + childId + ".s1",
            "r.c-" + childId + ".s2"
        );
        assertThat(
            persistence.getExecutedStepsInContextInOrderOfExecutionStarted(parentId).stream()
                .map(KanalarzPersistence.StepExecutedInfo::executionPath)
                .toList()
        ).containsExactly(
            "r.c-" + parentId + ".s0",
            "r.c-" + childId + ".s0",
            "r.c-" + childId + ".s1",
            "r.c-" + parentId + ".s1",
            "r.c-" + parentId + ".s0",
            "r.c-" + childId + ".s2",
            "r.c-" + parentId + ".s1"
        );
    }

    @Test
    void namedParentRerunShouldAllowResumingReplayOnlyFailedChildInsideParentForkJoin() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        AtomicBoolean shouldFailChild = new AtomicBoolean(true);

        Consumer<KanalarzContext> childJob = child -> {
            steps.submitPostA("child-1");
            steps.submitPostB("child-2");
            if (shouldFailChild.getAndSet(false)) {
                throw new RuntimeException("child-failure");
            }
            steps.submitPostA("child-after-replay");
        };

        kanalarz.newContext().resumes(parentId).consume(parent -> {
            steps.submitPostA("parent-1-start");
            assertThatThrownBy(() ->
                Kanalarz.forkConsume(List.of(1), ignored ->
                    kanalarz.newContext()
                        .resumes(childId)
                        .option(Kanalarz.Option.DEFER_ROLLBACK)
                        .consume(childJob)
                )
            ).hasMessageContaining("child-failure");
            steps.submitPostB("parent-1-end");
        });

        assertThat(service.postsA.values()).containsExactlyInAnyOrder("parent-1-start", "child-1");
        assertThat(service.postsB.values()).containsExactlyInAnyOrder("child-2", "parent-1-end");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId)).hasSize(2);

        kanalarz.newContext().resumes(parentId).consume(parent -> {
            steps.submitPostA("parent-2-start");
            Kanalarz.forkConsume(List.of(1), ignored ->
                kanalarz.newContext().resumes(childId).consumeResumeReplay(childJob)
            );
            steps.submitPostB("parent-2-end");
        });

        assertThat(service.postsA.values())
            .containsExactlyInAnyOrder("parent-1-start", "child-1", "parent-2-start", "child-after-replay");
        assertThat(service.postsB.values()).containsExactlyInAnyOrder("child-2", "parent-1-end", "parent-2-end");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId)).hasSize(3);
        assertThat(
            persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId).stream()
                .map(KanalarzPersistence.StepExecutedInfo::executionPath)
                .toList()
        ).containsExactly(
            "r.c-" + childId + ".s0",
            "r.c-" + childId + ".s1",
            "r.c-" + childId + ".s2"
        );
        assertThat(
            persistence.getExecutedStepsInContextInOrderOfExecutionStarted(parentId).stream()
                .map(KanalarzPersistence.StepExecutedInfo::executionPath)
                .toList()
        ).containsExactly(
            "r.c-" + parentId + ".s0",
            "r.c-" + childId + ".s0",
            "r.c-" + childId + ".s1",
            "r.c-" + parentId + ".s2",
            "r.c-" + parentId + ".s0",
            "r.c-" + childId + ".s2",
            "r.c-" + parentId + ".s2"
        );
    }

    @Test
    void childShouldBeAbleToBeReplayedOutsideOfTheParent() {
        UUID childId = UUID.randomUUID();
        List<UUID> posts = new ArrayList<>();

        kanalarz.newContext().consume(ctx -> {
            posts.add(steps.submitPostA("parent"));
            kanalarz.newContext().resumes(childId).consume(ctx2 -> {
                posts.add(steps.submitPostA("child"));
                posts.add(steps.submitPostB("child"));
            });
            posts.add(steps.submitPostB("parent"));
        });

        assertThat(service.postsA.get(posts.get(0))).isEqualTo("parent");
        assertThat(service.postsA.get(posts.get(1))).isEqualTo("child");
        assertThat(service.postsA).hasSize(2);

        assertThat(service.postsB.get(posts.get(2))).isEqualTo("child");
        assertThat(service.postsB.get(posts.get(3))).isEqualTo("parent");
        assertThat(service.postsB).hasSize(2);

        kanalarz.newContext().resumes(childId).consumeResumeReplay(ctx -> {
            steps.submitPostA("child");
            steps.submitPostB("child");
            posts.add(steps.submitPostA("after-resume"));
        });

        assertThat(service.postsA).hasSize(3);
        assertThat(service.postsB).hasSize(2);
        assertThat(service.postsA.get(posts.get(4))).isEqualTo("after-resume");
    }

    @Test
    void childInForkJoinShouldBeAbleToBeReplayedOutsideOfTheParent() {
        UUID childId = UUID.randomUUID();
        List<UUID> posts = new ArrayList<>();
        List<UUID> child1Posts = new ArrayList<>();
        List<UUID> child2Posts = new ArrayList<>();


        kanalarz.newContext().consume(ctx -> {
            posts.add(steps.submitPostA("parent"));

            Kanalarz.forkConsume(List.of(1, 2), idx -> {
                var resumes = idx == 1 ? childId : UUID.randomUUID();
                var childPosts = idx == 1 ? child1Posts : child2Posts;
                kanalarz.newContext().resumes(resumes).consume(ctx2 -> {
                    childPosts.add(steps.submitPostA("child"));
                    childPosts.add(steps.submitPostB("child"));
                });
            });

            posts.add(steps.submitPostB("parent"));
        });

        assertThat(service.postsA.get(posts.get(0))).isEqualTo("parent");
        assertThat(service.postsA.get(child1Posts.get(0))).isEqualTo("child");
        assertThat(service.postsA.get(child2Posts.get(0))).isEqualTo("child");
        assertThat(service.postsA).hasSize(3);

        assertThat(service.postsB.get(child1Posts.get(1))).isEqualTo("child");
        assertThat(service.postsB.get(child2Posts.get(1))).isEqualTo("child");
        assertThat(service.postsB.get(posts.get(1))).isEqualTo("parent");
        assertThat(service.postsB).hasSize(3);

        kanalarz.newContext().resumes(childId).consumeResumeReplay(ctx -> {
            steps.submitPostA("child");
            steps.submitPostB("child");
            child1Posts.add(steps.submitPostA("after-resume"));
        });

        assertThat(service.postsA).hasSize(4);
        assertThat(service.postsB).hasSize(3);
        assertThat(service.postsA.get(child1Posts.get(2))).isEqualTo("after-resume");
    }

    @Test
    void childWithOnlyNestedStepsShouldBeAbleToBeReplayedOutsideOfTheParent() {
        UUID childId = UUID.randomUUID();
        List<UUID> posts = new ArrayList<>();

        kanalarz.newContext().consume(ctx -> {
            posts.add(steps.submitPostA("parent"));
            kanalarz.newContext().resumes(childId).consume(ctx2 ->
                kanalarz.newContext().consume(ctx3 -> {
                    posts.add(steps.submitPostA("child"));
                    posts.add(steps.submitPostB("child"));
                })
            );
            posts.add(steps.submitPostB("parent"));
        });

        assertThat(service.postsA.get(posts.get(0))).isEqualTo("parent");
        assertThat(service.postsA.get(posts.get(1))).isEqualTo("child");
        assertThat(service.postsA).hasSize(2);

        assertThat(service.postsB.get(posts.get(2))).isEqualTo("child");
        assertThat(service.postsB.get(posts.get(3))).isEqualTo("parent");
        assertThat(service.postsB).hasSize(2);

        kanalarz.newContext().resumes(childId).consumeResumeReplay(ctx -> {
            kanalarz.newContext().consume(ctx2 -> {
                steps.submitPostA("child");
                steps.submitPostB("child");
            });
            posts.add(steps.submitPostA("after-resume"));
        });

        assertThat(service.postsA).hasSize(3);
        assertThat(service.postsB).hasSize(2);
        assertThat(service.postsA.get(posts.get(4))).isEqualTo("after-resume");
    }

    @Test
    void resumedNamedContextInsideParentShouldKeepItsOwnPersistedPath() {
        UUID childId = UUID.randomUUID();

        kanalarz.newContext().resumes(childId).consume(ctx -> steps.submitPostA("seed"));

        kanalarz.newContext().consume(ctx -> {
            steps.submitPostA("parent");
            kanalarz.newContext().resumes(childId).consume(ctx2 -> steps.submitPostB("child"));
        });

        assertThat(
            persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId).stream()
                .map(KanalarzPersistence.StepExecutedInfo::executionPath)
                .toList()
        ).allMatch(it -> it.startsWith("r.c-" + childId + "."));
    }

    @Test
    void replayingOneNamedChildShouldIgnoreAnotherNamedSibling() {
        UUID childAId = UUID.randomUUID();
        UUID childBId = UUID.randomUUID();
        List<UUID> childAPosts = new ArrayList<>();
        List<UUID> childBPosts = new ArrayList<>();

        kanalarz.newContext().consume(ctx -> {
            kanalarz.newContext().resumes(childAId).consume(ctx2 -> {
                childAPosts.add(steps.submitPostA("child"));
                childAPosts.add(steps.submitPostB("child"));
            });
            kanalarz.newContext().resumes(childBId).consume(ctx2 -> {
                childBPosts.add(steps.submitPostA("child"));
                childBPosts.add(steps.submitPostB("child"));
            });
        });

        assertThat(service.postsA).hasSize(2);
        assertThat(service.postsB).hasSize(2);
        assertThat(service.postsA.get(childAPosts.get(0))).isEqualTo("child");
        assertThat(service.postsA.get(childBPosts.get(0))).isEqualTo("child");
        assertThat(service.postsB.get(childAPosts.get(1))).isEqualTo("child");
        assertThat(service.postsB.get(childBPosts.get(1))).isEqualTo("child");

        kanalarz.newContext().resumes(childAId).consumeResumeReplay(ctx -> {
            steps.submitPostA("child");
            steps.submitPostB("child");
            childAPosts.add(steps.submitPostA("after-resume"));
        });

        assertThat(service.postsA).hasSize(3);
        assertThat(service.postsB).hasSize(2);
        assertThat(service.postsA.get(childAPosts.get(2))).isEqualTo("after-resume");
        assertThat(service.postsA.get(childBPosts.get(0))).isEqualTo("child");
        assertThat(service.postsB.get(childBPosts.get(1))).isEqualTo("child");
    }

    @Test
    void replayingSameNamedChildNestedInsideItselfShouldFailClearly() {
        UUID childId = UUID.randomUUID();
        List<UUID> posts = new ArrayList<>();

        assertThatThrownBy(() ->
            kanalarz.newContext().consume(ctx ->
                kanalarz.newContext().resumes(childId).consume(ctx2 -> {
                    posts.add(steps.submitPostA("outer-child"));
                    kanalarz.newContext().resumes(childId).consume(ctx3 -> {
                        posts.add(steps.submitPostB("inner-child"));
                    });
                })
            )
        )
            .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasCauseExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
            .hasRootCauseExactlyInstanceOf(KanalarzException.KanalarzIllegalUsageException.class)
            .rootCause()
            .hasMessageContaining("nested inside itself");

        assertThat(posts).hasSize(1);
        assertThat(service.postsA).isEmpty();
        assertThat(service.postsB).isEmpty();
    }

    @Test
    void parentResumeReplayShouldAlsoReplayNamedResumedChild() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        AtomicBoolean shouldFail = new AtomicBoolean(true);

        Consumer<KanalarzContext> job = ctx -> {
            steps.submitPostA("parent-start");
            kanalarz.newContext().resumes(childId).consume(child -> {
                steps.submitPostB("child-step");
            });

            if (shouldFail.getAndSet(false)) {
                throw new RuntimeException("fail-parent");
            }

            steps.submitPostA("parent-end");
        };

        assertThatThrownBy(() ->
            kanalarz.newContext().resumes(parentId).option(Kanalarz.Option.DEFER_ROLLBACK).consume(job)
        ).hasMessageContaining("fail-parent");

        assertThat(service.postsA.values()).containsExactly("parent-start");
        assertThat(service.postsB.values()).containsExactly("child-step");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId))
            .hasSize(1);

        kanalarz.newContext().resumes(parentId).consumeResumeReplay(job);

        assertThat(service.postsA.values()).containsExactlyInAnyOrder("parent-start", "parent-end");
        assertThat(service.postsB.values()).containsExactly("child-step");
        assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(childId))
            .hasSize(1);

        kanalarz.newContext().resumes(childId).rollbackNow();
        assertThat(service.postsA.values()).containsExactlyInAnyOrder("parent-start", "parent-end");
        assertThat(service.postsB.values()).isEmpty();

        kanalarz.newContext().resumes(parentId).rollbackNow();
        assertThat(service.postsA.values()).isEmpty();
        assertThat(service.postsB.values()).isEmpty();
    }

    @Test
    void childContextShouldInheritInitialContextCopyFromParent() {
        kanalarz.newContext()
            .metadata("test-1", "value-1")
            .consume(ctx -> {
                ctx.putMetadata("test-2", "value-2");
                Kanalarz.contextStackOrThrow().context().putMetadata("test-3", "value-3");

                assertThat(ctx.fullMetadata())
                    .isEqualTo(Kanalarz.contextStackOrThrow().context().fullMetadata())
                    .isEqualTo(Map.of(
                        "test-1", "value-1",
                        "test-2", "value-2",
                        "test-3", "value-3"
                    ));

                kanalarz.newContext()
                    .metadata("test-1", "override-1")
                    .metadata("child-test-1", "value-1")
                    .consume(ctx2 -> {

                        assertThat(ctx2.fullMetadata())
                            .isEqualTo(Kanalarz.contextStackOrThrow().context().fullMetadata())
                            .isEqualTo(Map.of(
                                "test-1", "override-1",
                                "test-2", "value-2",
                                "test-3", "value-3",
                                "child-test-1", "value-1"
                            ));

                        ctx2.putMetadata("child-test-2", "value-2");
                        ctx2.putMetadata("test-2", "override-2");

                        assertThat(ctx2.fullMetadata())
                            .isEqualTo(Kanalarz.contextStackOrThrow().context().fullMetadata())
                            .isEqualTo(Map.of(
                                "test-1", "override-1",
                                "test-2", "override-2",
                                "test-3", "value-3",
                                "child-test-1", "value-1",
                                "child-test-2", "value-2"
                            ));

                        Kanalarz.contextStackOrThrow().context().putMetadata("child-test-3", "value-3");
                    });

                assertThat(ctx.fullMetadata())
                    .isEqualTo(Kanalarz.contextStackOrThrow().context().fullMetadata())
                    .isEqualTo(Map.of(
                        "test-1", "value-1",
                        "test-2", "value-2",
                        "test-3", "value-3"
                    ));
            });
    }
}
