package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Mark a method as rollback handler for a step.
 * <p>
 * Rollback parameters are matched by parameter name (or {@link Arg}) to rollforward parameters.
 * Rollforward output can be injected with {@link RollforwardOut}.
 * Types and nullability are validated when the Spring context starts.
 * <p>
 * <b>If history is persisted, keep rollback signatures backward compatible for already stored steps.</b>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Inherited
@Documented
@Reflective
@NullMarked
public @interface Rollback {

    /**
     * Identifier of the step this rollback is attached to.
     * @return step identifier
     */
    String value();

    /**
     * If true, rollback errors from this method do not stop the overall rollback sequence.
     * A failed rollback event is still emitted.
     * @return true when rollback should be treated as fallible
     */
    boolean fallible() default false;
}
