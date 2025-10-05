package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

/**
 * Annotation used to make a method into a step. Calls to this method will be intercepted and raise
 * step events which can be used for observability or to rollback the step later. <b>This works using
 * a spring proxy so make sure you self-inject when calling another step in the same class within.</b>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Documented
@Reflective
public @interface Step {

    /** A globally-unique identifier for the step. <br><b>If you store the steps in a database you
     * probably need a migration if you ever change any step's identifier if it has ever ran!</b> */
    @NonNull
    String identifier();

    /** Determines whether the step is fallible. <br> <b>Fallible steps must return an instance of the StepOut
     * class from this library so when they fail the exception can be wrapped in the StepOut and returned. Fallible
     * steps must never return a null. This will cause the pipeline to fail due to bad usage. The spring context
     * will fail to initialize if a fallible step method is not marked as non-nullable.</b> */
    @NonNull
    boolean fallible() default false;
}
