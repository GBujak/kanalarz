package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
record ContextResumeState(@Nullable String basePath) {}
