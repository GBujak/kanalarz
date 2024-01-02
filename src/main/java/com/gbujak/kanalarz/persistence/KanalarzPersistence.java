package com.gbujak.kanalarz.persistence;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface KanalarzPersistence<PipelineIdentifier, StepIdentifier> {

    void persistPipeline(PipelineIdentifier pipelineIdentifier);

    void persistStepResult(StepIdentifier identifier, Object value);

    <T> T load(StepIdentifier identifier, Class<T> clazz);
}
