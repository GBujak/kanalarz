package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.UUID;

@NullMarked
class KanalarzPersistenceExceptionWrapper implements KanalarzPersistence {

    private final KanalarzPersistence persistence;

    KanalarzPersistenceExceptionWrapper(KanalarzPersistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public void stepStarted(StepStartedEvent stepStartedEvent) {
        try {
            persistence.stepStarted(stepStartedEvent);
        } catch (RuntimeException e) {
            throw new KanalarzException.KanalarzPersistenceException(e);
        }
    }

    @Override
    public void stepCompleted(StepCompletedEvent stepCompletedEvent) {
        try {
            persistence.stepCompleted(stepCompletedEvent);
        } catch (RuntimeException e) {
            throw new KanalarzException.KanalarzPersistenceException(e);
        }
    }

    @Override
    public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecutionStarted(UUID contextId) {
        try {
            return persistence.getExecutedStepsInContextInOrderOfExecutionStarted(contextId);
        } catch (RuntimeException e) {
            throw new KanalarzException.KanalarzPersistenceException(e);
        }
    }
}
