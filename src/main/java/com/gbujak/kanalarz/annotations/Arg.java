package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/** Annotation for setting a custom name for a step argument instead of the method param name in the code. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Inherited
@Reflective
@Documented
@NullMarked
public @interface Arg {

    /**
     * Name to use for this argument.
     * @return argument name
     */
    String value();
}
