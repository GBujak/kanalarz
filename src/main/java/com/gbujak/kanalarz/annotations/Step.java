package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Mark a method as a Kanalarz step.
 * <p>
 * Kanalarz intercepts calls to this method, persists execution details, and can later replay or rollback it.
 * <b>This works through a Spring proxy, so self-inject when calling another step method in the same class.</b>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Inherited
@Documented
@Reflective
@NullMarked
public @interface Step {

    /**
     * Globally unique step identifier.
     * <br><b>If step history is persisted, changing this identifier may require a data migration.</b>
     * @return step identifier
     */
    String value();

    /**
     * Whether this step reports failures via {@code StepOut} instead of failing the pipeline immediately.
     * <br><b>Fallible steps must return non-null {@code StepOut<T>}.</b>
     * @return true when the step is fallible
     */
    boolean fallible() default false;
}
