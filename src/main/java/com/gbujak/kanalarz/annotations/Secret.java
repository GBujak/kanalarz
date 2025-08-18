package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Inherited
@Reflective
@Documented
public @interface Secret {
}
