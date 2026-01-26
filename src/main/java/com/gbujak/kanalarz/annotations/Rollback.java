package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Annotation for making a method the rollback for some step. Any params of a method
 * annotated with this must have the same name as some of the params of the rollforward
 * step - either by the param name in the code or by using the @Arg annotation.
 * The output of the rollforward param can be injected using the @RollforwardOut annotation.
 * <br>
 * Types and nullability are checked to be the same at the start of the spring context.
 * <br>
 * <b>If you store the steps in a database, make sure any changes to the rollback methods
 * are backwards-compatible, or you may need to fix the existing steps in a migration.
 * If you only want to change the param name you can use the @Arg annotation to preserve
 * backwards-compatibility. The order of the params doesn't matter.</b>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Documented
@Reflective
@NullMarked
public @interface Rollback {

    /** Identifier of the step this is a rollback for. */
    String value();

    /** If this is set to false the rollback will be interrupted when this method throws. If set to true,
     * the failure will be ignored and the rollback will continue. A step completed event will still be
     * emitted notifying that this rollback step failed. */
    boolean fallible() default false;
}
