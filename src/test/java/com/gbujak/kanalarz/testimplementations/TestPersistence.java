package com.gbujak.kanalarz.testimplementations;

import com.gbujak.kanalarz.KanalarzPersistence;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class TestPersistence implements KanalarzPersistence {

    private static final Logger log = LoggerFactory.getLogger(TestPersistence.class);

    public List<StepStartedEvent> stepStartedEvents = Collections.synchronizedList(new ArrayList<>());
    public List<StepCompletedEvent> stepCompletedEvents = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void stepStarted(StepStartedEvent stepStartedEvent) {
        stepStartedEvents.add(stepStartedEvent);
        log.info("Received step started event: {}", stepStartedEvent.toString());
    }

    @Override
    public void stepCompleted(StepCompletedEvent stepCompletedEvent) {
        stepCompletedEvents.add(stepCompletedEvent);
        log.info("Received step completed event: {}", stepCompletedEvent.toString());
    }

    @NotNull
    @Override
    public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecution(@NotNull UUID contextId) {
        return stepCompletedEvents.stream()
            .filter(it -> it.contextId().equals(contextId))
            .map(it -> new StepExecutedInfo(
                it.stepId(),
                it.stepIdentifier(),
                it.serializedExecutionResult(),
                it.parentStepId(),
                it.stepIsRollbackFor(),
                it.failed()
            ))
            .toList();
    }
}
