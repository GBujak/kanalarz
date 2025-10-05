package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

/**
 * Provide a description for the step. You can use placeholders that reference some step params
 * with <code>{paramName}</code>. The context will fail to start when a placeholder is detected
 * with a param name that can't be found. use <code>{{}}</code> for a brace character literal.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Reflective
public @interface StepDescription {

    /**
     * The value of the step description. You can use placeholders that reference some step params
     * with <code>{paramName}</code>. The context will fail to start when a placeholder is detected
     * with a param name that can't be found. use <code>{{}}</code> for a brace character literal.
     */
    @NonNull
    String value();
}
