package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Annotation used to mark a rollback without a rollforward step. Using this is the same as defining a regular
 * step with an empty body and a rollback that injects every parameter of the rollforward step. Additionally,
 * metadata is given to your application that the rollforward is virtual so you may decide to hide it from the user.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Documented
@Reflective
@NullMarked
public @interface RollbackOnly {

    /** A globally-unique identifier for the step. <br><b>If you store the steps in a database you
     * probably need a migration if you ever change any step's identifier if it has ever ran!</b> */
    String value();

    /** If this is set to false the rollback will be interrupted when this method throws. If set to true,
     * the failure will be ignored and the rollback will continue. A step completed event will still be
     * emitted notifying that this rollback step failed. */
    boolean fallible() default false;
}
