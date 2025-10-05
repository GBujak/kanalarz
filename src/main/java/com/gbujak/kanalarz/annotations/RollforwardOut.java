package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Used in rollback steps to inject the return value of the rollforward step.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Inherited
@Reflective
@Documented
public @interface RollforwardOut {
}
