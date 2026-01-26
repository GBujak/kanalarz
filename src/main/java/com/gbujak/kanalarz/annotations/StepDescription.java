package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

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
@NullMarked
public @interface StepDescription {

    /**
     * The value of the step description. You can use placeholders that reference some step params
     * with <code>{paramName}</code>. The context will fail to start when a placeholder is detected
     * with a param name that can't be found. use <code>{{}}</code> for a brace character literal.
     */
    String value();
}
