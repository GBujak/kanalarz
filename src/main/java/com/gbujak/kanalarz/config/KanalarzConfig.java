package com.gbujak.kanalarz.config;

import com.gbujak.kanalarz.generatedcode.GeneratedClass;
import com.gbujak.kanalarz.persistence.KanalarzPersistence;
import com.gbujak.kanalarz.pipeline.PipelineIdentifierGenerator;
import com.gbujak.kanalarz.step.StepIdentifierGenerator;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record KanalarzConfig<PipelineIdentifier, StepIdentifier>(
    KanalarzPersistence<PipelineIdentifier, StepIdentifier> persistence,
    PipelineIdentifierGenerator<PipelineIdentifier> pipelineIdentifierGenerator,
    StepIdentifierGenerator<PipelineIdentifier, StepIdentifier> stepIdentifierGenerator
) {

    private static final GeneratedClass x = new GeneratedClass();
}
