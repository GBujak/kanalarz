package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

/**
 * Annotation that marks a spring bean as a holder of steps to be scanned for steps to register in the pipeline
 * library step cache.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
@Reflective
public @interface StepsHolder {

    /**
     * Globally unique identifier for the step holder. <br><b>If you save the step info to a database and you change
     * any step holder identifier you probably need a migration if any step from this holder has ever ran!</b>
     */
    @NonNull
    String identifier();
}
