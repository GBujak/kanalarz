package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

/** Annotation for setting a custom name for a step argument instead of the method param name in the code. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Inherited
@Reflective
@Documented
public @interface Arg {

    /** @return The name for the argument */
    @NonNull
    String value();
}
