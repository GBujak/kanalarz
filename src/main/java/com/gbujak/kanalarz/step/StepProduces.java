package com.gbujak.kanalarz.step;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record StepProduces(Class<?> clazz, boolean producesMultiple) {
}