package com.gbujak.kanalarz.pipeline;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface PipelineIdentifierGenerator<PipelineIdentifier> {

    PipelineIdentifier generatePipelineIdentifier();
}
