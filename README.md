# Kanalarz

Simple Spring library for persistent atomic jobs made of steps with rollbacks. 

<https://central.sonatype.com/artifact/com.gbujak/kanalarz>

```
<dependency>
    <groupId>com.gbujak</groupId>
    <artifactId>kanalarz</artifactId>
    <version>2.2.0</version>
</dependency>
```

You write the steps with rollbacks, write a job that calls these steps, and
calling the rollbacks in case of a failure in the right order is handled
automatically. 

This library stores all the pipeline information using an interface that you
implement. This interface can save the pipeline information to the database
allowing you to resume and/or rollback the pipelines even if the java process
is killed in the middle of the pipeline.

[Definition here](src/main/java/com/gbujak/kanalarz/KanalarzPersistence.java)

**As a side effect, the persisted data will be a log of every step ever
executed, including parameters and return values.**

This library uses the Spring Framework BeanPostProcessor mechanism, similar to
Spring's `@Transactional`. (Watch out - when calling
steps through `this` you must self-inject like with `@Transactional`)

You can inject rollforward step parameters and return values in rollback steps.
The typesafety is validated when the spring context is starting. Any difference
in type of nullability will cause the context creation to fail.

This library determines the nullability by checking the most popular Java
annotations (including `@Nullmarked` on the package and on the class). Kotlin
nullability is checked using the `org.jetbrains.kotlin:kotlin-reflect` library.

```java
@Component 
@StepsHolder(identifier = "test-steps") 
class TestSteps {

    @Autowired private TestNameService testNameService;

    @NonNull
    @Step(identifier = "set-name")
    public Optional<String> setName(String newName) {
        return Optional.ofNullable(testNameService.set(newName));
    }

    @Rollback(forStep = "set-name")
    public void setNameRollback(
        String newName, // <-- determined by the name of the parameter
        @NonNull @RollforwardOut Optional<String> originalName
    ) {
        testNameService.set(originalName.orElse(null));
    } 
}
```

Using the steps inside of a pipeline:

```java
var testNewName = "test";
var exception = new RuntimeException("test");

assertThatThrownBy(() ->
    kanalarz.newContext().consume(ctx -> {
        testSteps.setName(testNewName);
        assertThat(testNameService.name()).isEqualTo(testNewName);
        throw exception;
    })
)
    .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
    .hasCause(exception);

assertThat(testNameService.name()).isNull(); // rollback was ran
```

### Example of a resumed context

```java
var testNewName = "test"; 
var testNewName2 = "test2"; 
var contextId = UUID.randomUUID();

assertThat(testNameService.name()).isNull();

kanalarz.newContext().resumes(contextId).consume(ctx -> {
    testSteps.setName(testNewName);
    testSteps.setName(testNewName2);
});

// Java process could be killed here, nothing 
// is persisted in memory for the resume to work
        
assertThatThrownBy(() ->
    kanalarz.newContext().resumes(contextId).consume(ctx -> {
        testSteps.setName(testNewName);
        testSteps.setName(testNewName2);

        // test service throws error when trying to set the same value again
        testSteps.setName(testNewName2); 
    })
)
    .isExactlyInstanceOf(KanalarzException.KanalarzStepFailedException.class)
    .hasCauseExactlyInstanceOf(TestNameService.NameServiceNameAlreadyThatValueException.class);

// rollbacks from the initial pipeline were also called
assertThat(testNameService.name()).isNull();
assertThat(persistence.getExecutedStepsInContextInOrderOfExecution(contextId)).hasSize(9);
```

[See more examples here](src/test/java/com/gbujak/kanalarz/BasicTests.java)


[Or in Kotlin](src/test/java/com/gbujak/kanalarz/BasicTestsKotlin.kt)

## Concurrency

The library fully supports executing steps concurrently.

Rollbacks will happen sequentially based on the time each step finished
executing.

Concurrent [resume replay](#resume-replay) is supported but out of order replay
must be enabled when starting the pipeline or Kanalarz will throw an exception
when steps are executed in non-deterministic order.

[Concurrent examples here.](src/test/java/com/gbujak/kanalarz/ConcurrentTests.java)

## Nested steps

The library allows you to arbitrarily nest steps. The rollback of the parent is
called **before** any of the rollbacks of the children. Parent steps with
rollbacks are not recommended.

## Resume replay

The library allows you to "resume replay" the pipelines. The code outside of the
steps will be ran again but the steps will return the value from the previous
run, allowing you to resume the whole pipeline from an arbitrary point.

**Note: pipelines are only safe to resume replay when the don't have any
side-effects outside of steps.**

Nested steps are fully supported for resume replay.

There are multiple setting you can use to determine how the library will handle
steps that are executed in a different order than in the original run of the
pipeline. 

You can choose to ignore the non-replayed steps, rollback only them leaving the
rest commited, or fail the entire pipeline and rollback everything if any
non-replayed steps remain at the end of the pipeline.


```java 
UUID contextId = UUID.randomUUID(); 
var exception = new RuntimeException();

assertThatThrownBy(() ->
    kanalarz.newContext()
        .resumes(contextId)
        .option(Kanalarz.Option.DEFER_ROLLBACK)
        .consume(ctx -> {
            assertThat(steps.add("test-1")).isEqualTo(List.of("test-1"));
            assertThat(steps.add("test-2")).isEqualTo(List.of("test-1", "test-2"));
            throw exception;
        })
)
    .isExactlyInstanceOf(KanalarzException.KanalarzThrownOutsideOfStepException.class)
    .hasCause(exception);

assertThat(service.getMessages()).isEqualTo(List.of("test-1", "test-2"));

kanalarz.newContext()
    .resumes(contextId)
    .consumeResumeReplay(ctx -> {
        assertThat(steps.add("test-1")).isEqualTo(List.of("test-1")); // replayed
        assertThat(steps.add("test-2")).isEqualTo(List.of("test-1", "test-2")); // replayed

        assertThat(steps.add("test-3")).isEqualTo(List.of("test-1", "test-2", "test-3")); // real
        assertThat(steps.add("test-3")).isEqualTo(List.of("test-1", "test-2", "test-3", "test-3")); // real
    });

assertThat(service.getMessages()).isEqualTo(List.of("test-1", "test-2", "test-3", "test-3"));
```

[See more examples here](src/test/java/com/gbujak/kanalarz/ResumeReplayTests.java)
