package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Mark the param or return value of some step as secret. This does nothing except
 * set the boolean field in all the related step events. It's on you to actually hide
 * the param from the user.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Inherited
@Reflective
@Documented
public @interface Secret {
}
