# Kanalarz

Simple Spring library for persistent atomic jobs made of steps with rollbacks.

<https://central.sonatype.com/artifact/com.gbujak/kanalarz>

```
<dependency>
    <groupId>com.gbujak</groupId>
    <artifactId>kanalarz</artifactId>
    <version>4.0.0</version>
</dependency>
```

[![javadoc](https://javadoc.io/badge2/com.gbujak/kanalarz/javadoc.svg)](https://javadoc.io/doc/com.gbujak/kanalarz)

You define step methods with rollback handlers, then run pipelines that call
those steps. If a pipeline fails, Kanalarz executes rollbacks automatically in
the correct order.

Pipeline state is persisted through interfaces you implement. This allows resume
replay and rollback even if the JVM process dies mid-pipeline.

[Definition here](src/main/java/com/gbujak/kanalarz/KanalarzPersistence.java)

**As a side effect, the persisted data will be a log of every step ever
executed, including parameters and return values.**

Kanalarz uses Spring `BeanPostProcessor`, similarly to `@Transactional`.
When calling steps inside the same class self inject to avoid skipping the spring proxy!

You can inject rollforward parameters and outputs into rollback methods.
Type safety is validated during Spring context startup. Any difference
in nullability will cause the context creation to fail.

Nullability is inferred from Java annotations (including `@NullMarked` on package/class). Kotlin
nullability is checked using the `org.jetbrains.kotlin:kotlin-reflect` library.

## Why Kanalarz

Kanalarz is for orchestrating side effects where DB transaction is not
enough (like when calling external services). It is not an alternative to
`@Transactional`.

It lets you write procedural Java/Kotlin code, annotate steps and rollbacks,
and get durable saga-like orchestration with strict replay for little effort.

## Quick start

1. `@Import(KanalarzConfiguration.class)`.
2. Implement `KanalarzPersistence`. [Test implementation](https://github.com/GBujak/kanalarz/blob/master/src/test/java/com/gbujak/kanalarz/testimplementations/TestPersistence.java)
3. Implement `KanalarzSerialization`. [Test implementation](https://github.com/GBujak/kanalarz/blob/master/src/test/java/com/gbujak/kanalarz/testimplementations/TestSerialization.java)
4. Inject `Kanalarz` and execute pipelines via `newContext()...`.

```java
// Example Kanalarz step holding bean
@Component 
@StepsHolder("account-steps") 
class AccountSteps {

    @Autowired private AccountService accountService;

    @NonNull
    @Step("set-name")
    public Optional<String> setName(String newName) {
        // returns previous value
        return Optional.ofNullable(accountService.setName(newName));
    }

    @Rollback("set-name")
    public void rollbackSetName(
        String newName, // matched by name
        @NonNull @RollforwardOut Optional<String> originalName
    ) {
        accountService.setName(originalName.orElse(null));
    } 
}
```

Using the steps inside a pipeline:

```java
var contextId = UUID.randomUUID();
var crash = new RuntimeException("boom");

assertThatThrownBy(() ->
    kanalarz.newContext().resumes(contextId).consume(ctx -> {
        accountSteps.setName("alice");
        assertThat(accountService.name()).isEqualTo("alice");

        // anything thrown in a context triggers rollback
        throw crash;
    })
)
    .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
    .hasCause(crash);

assertThat(accountService.name()).isNull(); // rollback executed
```

### Example of a resumed context

```java
var contextId = UUID.randomUUID();

assertThat(accountService.name()).isNull();

kanalarz.newContext().resumes(contextId).consume(ctx -> {
    accountSteps.setName("alice");
    accountSteps.setName("bob");
});

// Java process could be killed here, nothing 
// is persisted in memory for the resume to work
        
assertThatThrownBy(() ->
    kanalarz.newContext().resumes(contextId).consume(ctx -> {
        accountSteps.setName("alice");
        accountSteps.setName("bob");

        // fails: trying to set the same value again
        accountSteps.setName("bob"); 
    })
)
    .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
    .hasCauseExactlyInstanceOf(AccountService.NameAlreadySetException.class);

// rollbacks from the initial pipeline were also called
assertThat(accountService.name()).isNull();
assertThat(persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId)).hasSize(9);
```

[See more examples here](src/test/java/com/gbujak/kanalarz/BasicTests.java)

[Or in Kotlin](src/test/java/com/gbujak/kanalarz/BasicTestsKotlin.kt)

## Concurrency

The library supports concurrent step execution.

Rollbacks run sequentially in reverse completion order.

Use `Kanalarz.forkJoin(...)` or `Kanalarz.forkConsume(...)` inside a pipeline.
Kanalarz propagates pipeline context to each forked task and assigns each task a
stable execution path. During resume replay, those execution paths are used to
match previously executed steps.

[Concurrent examples here.](src/test/java/com/gbujak/kanalarz/ConcurrentTests.java)

## Nested steps

The library allows arbitrarily nested steps and contexts. The rollback of the parent is
called **after** any of the rollbacks of the children. Parent steps with
rollbacks are not recommended.

## Resume replay

Resume replay reruns pipeline code outside of step methods, 
while steps aren't executed, but immediately return
persisted results from the previous runs.

**Note: pipelines are only safe to resume replay when they don't have any
side-effects outside of steps.**

Nested steps are fully supported for resume replay, including nested contexts
and fork-join tasks.

Resume replay is strict:

1. A replayed step must match the previously persisted step at the same execution path.
2. Step identifier and serialized arguments must match.
3. If a new step starts before all persisted steps for that replay segment are replayed, replay fails with
   `KanalarzNewStepBeforeReplayEndedException`.
4. If replay ends while persisted steps are still unreplayed, replay fails with
   `KanalarzNotAllStepsReplayedException`.

If a previously persisted step was marked as failed, Kanalarz reruns that step
instead of replaying a cached return.

[Pipeline configuration option docs](https://javadoc.io/doc/com.gbujak/kanalarz/latest/com/gbujak/kanalarz/Kanalarz.Option.html)

```java 
UUID contextId = UUID.randomUUID(); 
var crash = new RuntimeException("boom");

assertThatThrownBy(() ->
    kanalarz.newContext()
        .resumes(contextId)
        .option(Kanalarz.Option.DEFER_ROLLBACK)
        .consume(ctx -> {
            assertThat(steps.add("first")).isEqualTo(List.of("first"));
            assertThat(steps.add("second")).isEqualTo(List.of("first", "second"));
            throw crash;
        })
)
    .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
    .hasCause(crash);

assertThat(service.getMessages()).isEqualTo(List.of("first", "second"));

kanalarz.newContext()
    .resumes(contextId)
    .consumeResumeReplay(ctx -> {
        // replayed from previous execution (step body not re-run)
        assertThat(steps.add("first")).isEqualTo(List.of("first"));
        assertThat(steps.add("second")).isEqualTo(List.of("first", "second"));

        // new steps run normally after replay is fully drained
        assertThat(steps.add("third")).isEqualTo(List.of("first", "second", "third"));
        assertThat(steps.add("third")).isEqualTo(List.of("first", "second", "third", "third"));
    });

assertThat(service.getMessages()).isEqualTo(List.of("first", "second", "third", "third"));
```

[See more examples here](src/test/java/com/gbujak/kanalarz/ResumeReplayTests.java)

## Advanced features

### Parallelism model

Parallelism is explicit and context-aware:

1. Use `forkJoin(List<X>, Function<X,Y>)` to run parallel tasks and collect results.
2. Use `forkConsume(List<X>, Consumer<X>)` for fire-and-wait side effect tasks.
3. Use overloads with `maxParallelism` to limit running virtual threads.

Important semantics:

1. Forked tasks inherit the Kanalarz context automatically.
2. `forkJoin` preserves input order in its returned list, regardless of task completion order.
3. Each forked task receives its own execution-path branch; resume replay matches by those paths.
4. To replay parallel sections, rerun logically equivalent fork structure and step calls.
5. Rollback remains sequential after failure.

[Concurrent examples here.](src/test/java/com/gbujak/kanalarz/ConcurrentTests.java)

### Deferred rollback

Use `DEFER_ROLLBACK` when you want to decide rollback timing explicitly.

```java
var contextId = UUID.randomUUID();

assertThatThrownBy(() ->
    kanalarz.newContext()
        .resumes(contextId)
        .option(Kanalarz.Option.DEFER_ROLLBACK)
        .consume(ctx -> {
            steps.add("created");
            throw new RuntimeException("fail");
        })
);

// rollback can be triggered later
kanalarz.newContext().resumes(contextId).rollbackNow();
```

### Rollback failure strategy

If rollback already failed for some steps, choose one strategy:

1. `RETRY_FAILED_ROLLBACKS` to attempt those rollback steps again.
2. `SKIP_FAILED_ROLLBACKS` to skip them.
3. `ALL_ROLLBACK_STEPS_FALLIBLE` to continue rollback even when rollback steps throw.

[Example here.](src/test/java/com/gbujak/kanalarz/RetryRollbackTest.java)

### Cancellation

You can cancel a running context from another thread:

1. `Kanalarz.cancelContext(contextId)` keeps normal rollback behavior.
2. `Kanalarz.cancelContextForceDeferRollback(contextId)` forces deferred rollback.

[Examples here.](src/test/java/com/gbujak/kanalarz/CancellingTests.java)

### Nested contexts and replay isolation

Subcontexts maintain independent timelines and can be replayed independently
(including subcontexts started from fork-join branches).

[Examples here.](src/test/java/com/gbujak/kanalarz/NestedContextsTests.java)

### Fallible steps

Use `@Step(fallible = true)` and return `StepOut<T>` to automatically catch exceptions from steps.

[Examples here.](src/test/java/com/gbujak/kanalarz/BasicTests.java)
