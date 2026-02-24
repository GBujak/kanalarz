package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Mark a rollback-only operation that has no separate rollforward method.
 * <p>
 * Conceptually this behaves like a virtual step with an empty rollforward and this method as rollback.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Inherited
@Documented
@Reflective
@NullMarked
public @interface RollbackOnly {

    /**
     * Globally unique identifier for the virtual step.
     * <br><b>If history is persisted, changing this identifier may require a data migration.</b>
     * @return virtual step identifier
     */
    String value();

    /**
     * If true, rollback errors from this method do not stop the overall rollback sequence.
     * A failed rollback event is still emitted.
     * @return true when rollback should be treated as fallible
     */
    boolean fallible() default false;
}
