package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Provide a human-readable description for a step.
 * <p>
 * Use placeholders with <code>{paramName}</code> to reference step parameters.
 * Use <code>{{literal}}</code> to render literal braces.
 * Spring context initialization fails if any placeholder references an unknown parameter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Reflective
@NullMarked
public @interface StepDescription {

    /**
     * Description template value.
     * @return template string
     */
    String value();
}
