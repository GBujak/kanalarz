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
}
