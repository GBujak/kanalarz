package com.gbujak.kanalarz.step;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface StepIdentifierGenerator<PipelineIdentifier, StepIdentifier> {

    StepIdentifier generateStepIdentifier(PipelineIdentifier pipelineIdentifier);
}
