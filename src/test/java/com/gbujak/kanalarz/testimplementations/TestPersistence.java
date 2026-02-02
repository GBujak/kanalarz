package com.gbujak.kanalarz.testimplementations;

import com.gbujak.kanalarz.KanalarzPersistence;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@NullMarked
public class TestPersistence implements KanalarzPersistence {

    private static final Logger log = LoggerFactory.getLogger(TestPersistence.class);

    public List<StepStartedEvent> stepStartedEvents = Collections.synchronizedList(new ArrayList<>());
    public List<StepCompletedEvent> stepCompletedEvents = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void stepStarted(StepStartedEvent stepStartedEvent) {
        stepStartedEvents.add(stepStartedEvent);
    }

    @Override
    public void stepCompleted(StepCompletedEvent stepCompletedEvent) {
        stepCompletedEvents.add(stepCompletedEvent);
    }

    @Override
    public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecutionStarted(UUID contextId) {
        return stepStartedEvents.stream()
            .filter(it -> it.contexts().contains(contextId))
            .map(started -> stepCompletedEvents.stream().filter(completed ->
                completed.stepId().equals(started.stepId())).findFirst().orElseThrow()
            )
            .map(it -> new StepExecutedInfo(
                it.contexts(),
                it.stepId(),
                it.stepIdentifier(),
                it.serializedExecutionResult(),
                it.parentStepId(),
                it.stepIsRollbackFor(),
                it.failed(),
                it.executionPath()
            ))
            .toList();
    }
}
