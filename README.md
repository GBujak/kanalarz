# Kanalarz

Simple Spring library for persistent rollbacks - atomic jobs made of steps that
can define their own rollback actions. 

You write the steps with rollbacks, write a job that calls these steps, and
calling the rollbacks in case of a failure in the right order is handled for you
automatically. 

This library stores all the pipeline information using a simple interface that
you implement in your application. This interface can save the pipeline
information to the database allowing you to resume and/or rollback the pipelines
even if the java process is killed in the middle of the pipeline.

**As a side effect, the persisted data will be a log of every step ever
executed, including its parameters and the return value which is great for
observability.**

This library uses the Spring Framework BeanPostProcessor mechanism, similar to
Spring's `@Transactional`. The type safety of the rollforward and rollback steps
is verified when the Spring context is being created. (Watch out - when calling
steps through `this` you must self-inject like with `@Transactional`)

If you accidentaly try to inject a parameter or the return value of the
rollforward step in the rollback step but the type or nullability is different,
the context will fail to start with a descriptive error telling you what type
and what nullability was expected for which argument.

This library determines the nullability by checking the most popular Java
annotations (including `@Nullmarked` on the package and on the class) and by
checking the Kotlin nullability using the `org.jetbrains.kotlin:kotlin-reflect`
library.

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

## Nested steps

The library allows you to arbitrarily nest steps. The rollback of the parent is
called **before** any of the rollbacks of the children. It is not recommended to
have parent steps with rollbacks at all.

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
